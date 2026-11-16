package com.lofi.cam

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.Crop
import androidx.media3.effect.RgbFilter
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.Effects
import androidx.media3.transformer.Transformer
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Re-encodes the camera recording at the selected output height with Media3,
 * then remuxes the H.264/AAC result into a 3GP container.
 */
@OptIn(UnstableApi::class)
object VideoLoFiProcessor {
    fun convert(
        context: Context,
        inputPath: String,
        outputPath: String,
        targetHeightP: Int,
        targetFps: Int,
        outputFormat: String
    ) {
        val input = File(inputPath)
        require(input.exists() && input.length() > 0L) { "File rekaman kosong atau tidak ditemukan" }

        // H.264 encoders require practical minimum dimensions and even pixel sizes.
        // Values 1p-15p therefore map to the smallest usable 16-pixel height.
        val outputHeight = if (targetHeightP <= 0) 0 else targetHeightP.coerceIn(16, 150).let {
            if (it % 2 == 0) it else it - 1
        }
        val scaledMp4 = File(context.cacheDir, "scaled_${System.currentTimeMillis()}.mp4")
        try {
            transcodeToHeight(context, input, scaledMp4, outputHeight)
            if (outputFormat == "MP4") {
                scaledMp4.copyTo(File(outputPath), overwrite = true)
            } else {
                remuxWithMetadata(scaledMp4, File(outputPath), "3GP", null)
            }
        } finally {
            scaledMp4.delete()
        }
        require(File(outputPath).exists() && File(outputPath).length() > 0L) {
            "File hasil 3GP kosong"
        }
    }

    fun convertSegments(
        context: Context,
        inputPaths: List<String>,
        outputPath: String,
        targetHeightP: Int,
        outputFormat: String,
        filterIndex: Int = 0,
        zoomFactor: Float = 1f,
        geoLocation: Pair<Float, Float>? = null
    ) {
        require(inputPaths.isNotEmpty()) { "Tidak ada segmen video" }
        val inputs = inputPaths.map { File(it) }
        require(inputs.all { it.exists() && it.length() > 0L }) { "Salah satu segmen video kosong" }
        val height = if (targetHeightP <= 0) 0 else targetHeightP.coerceIn(16, 150).let { if (it % 2 == 0) it else it - 1 }
        val merged = File(context.cacheDir, "merged_${System.currentTimeMillis()}.mp4")
        try {
            transcodeSegments(context, inputs, merged, height, filterIndex, zoomFactor)
            remuxWithMetadata(merged, File(outputPath), outputFormat, geoLocation)
        } finally {
            merged.delete()
            inputs.forEach { it.delete() }
        }
        require(File(outputPath).exists() && File(outputPath).length() > 0L) { "Hasil gabungan video kosong" }
    }

    private fun transcodeSegments(context: Context, inputs: List<File>, output: File, height: Int, filterIndex: Int, zoomFactor: Float) {
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Exception?>(null)
        Handler(Looper.getMainLooper()).post {
            try {
                val items = inputs.map { file ->
                    val videoEffects = mutableListOf<androidx.media3.common.Effect>()
                    if (filterIndex == 2) videoEffects.add(RgbFilter.createGrayscaleFilter())
                    if (zoomFactor > 1f) {
                        val edge = (1f / zoomFactor.coerceIn(1f, 100f)).coerceIn(0.01f, 1f)
                        videoEffects.add(Crop(-edge, edge, -edge, edge))
                    }
                    if (height > 0) videoEffects.add(Presentation.createForHeight(height))
                    val effects = Effects(listOf(PixelCrunchAudioProcessor(height)), videoEffects)
                    EditedMediaItem.Builder(MediaItem.fromUri(android.net.Uri.fromFile(file)))
                        .setEffects(effects).build()
                }
                @Suppress("DEPRECATION")
                val sequence = EditedMediaItemSequence(items)
                val composition = Composition.Builder(sequence).build()
                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        finished.countDown()
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        failure.set(IllegalStateException("Gagal menggabungkan video kamera", exportException))
                        finished.countDown()
                    }
                }
                Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(listener)
                    .build()
                    .start(composition, output.absolutePath)
            } catch (e: Exception) {
                failure.set(e)
                finished.countDown()
            }
        }
        if (!finished.await(8, TimeUnit.MINUTES)) throw IllegalStateException("Penggabungan video terlalu lama")
        failure.get()?.let { throw it }
        require(output.exists() && output.length() > 0L) { "File gabungan tidak terbentuk" }
    }

    private fun transcodeToHeight(context: Context, input: File, output: File, height: Int) {
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Exception?>(null)
        val mainHandler = Handler(Looper.getMainLooper())

        mainHandler.post {
            try {
                val effects = Effects(
                    listOf(PixelCrunchAudioProcessor(height)),
                    if (height > 0) listOf(Presentation.createForHeight(height)) else emptyList()
                )
                val editedItem = EditedMediaItem.Builder(
                    MediaItem.fromUri(android.net.Uri.fromFile(input))
                ).setEffects(effects).build()

                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        finished.countDown()
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        failure.set(IllegalStateException("Gagal mengubah resolusi video", exportException))
                        finished.countDown()
                    }
                }

                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(listener)
                    .build()
                transformer.start(editedItem, output.absolutePath)
            } catch (e: Exception) {
                failure.set(e)
                finished.countDown()
            }
        }

        if (!finished.await(5, TimeUnit.MINUTES)) {
            throw IllegalStateException("Proses resolusi video terlalu lama")
        }
        failure.get()?.let { throw it }
        require(output.exists() && output.length() > 0L) {
            "Hasil konversi resolusi tidak ditemukan"
        }
    }

    private fun remuxWithMetadata(input: File, output: File, format: String, location: Pair<Float, Float>?) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(input.absolutePath)
            val trackMap = mutableMapOf<Int, Int>()
            var hasVideo = false
            var maxBuffer = 1024 * 1024

            for (i in 0 until extractor.trackCount) {
                val trackFormat = extractor.getTrackFormat(i)
                val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: continue
                val supported = mime == MediaFormat.MIMETYPE_VIDEO_AVC ||
                    mime == MediaFormat.MIMETYPE_VIDEO_H263 ||
                    mime == MediaFormat.MIMETYPE_VIDEO_MPEG4 ||
                    mime == MediaFormat.MIMETYPE_AUDIO_AAC ||
                    mime == MediaFormat.MIMETYPE_AUDIO_AMR_NB ||
                    mime == MediaFormat.MIMETYPE_AUDIO_AMR_WB
                if (!supported) continue

                if (muxer == null) {
                    muxer = MediaMuxer(
                        output.absolutePath,
                        if (format == "MP4") MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                        else MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP
                    )
                    location?.let { (lat, lon) -> muxer?.setLocation(lat, lon) }
                }
                val outTrack = muxer!!.addTrack(trackFormat)
                trackMap[i] = outTrack
                if (mime.startsWith("video/")) hasVideo = true
                if (trackFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxBuffer = maxOf(maxBuffer, trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
            }

            require(hasVideo && trackMap.isNotEmpty()) {
                "Format video dari kamera tidak didukung oleh kontainer hasil"
            }

            val writer = muxer ?: error("Gagal menyiapkan file 3GP")
            writer.start()
            started = true
            trackMap.keys.forEach { extractor.selectTrack(it) }
            val buffer = ByteBuffer.allocateDirect(maxBuffer)
            val info = MediaCodec.BufferInfo()

            while (true) {
                val sourceTrack = extractor.sampleTrackIndex
                if (sourceTrack < 0) break
                val destinationTrack = trackMap[sourceTrack]
                if (destinationTrack == null) {
                    extractor.advance()
                    continue
                }
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime.coerceAtLeast(0L), extractor.sampleFlags)
                writer.writeSampleData(destinationTrack, buffer, info)
                extractor.advance()
            }
        } finally {
            if (started) {
                try {
                    muxer?.stop()
                } catch (e: Exception) {
                    output.delete()
                    throw IllegalStateException("Gagal menyelesaikan file video", e)
                }
            }
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            if (!started) output.delete()
        }
    }
}
