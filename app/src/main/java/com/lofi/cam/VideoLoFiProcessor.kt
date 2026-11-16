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
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.effect.FrameDropEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
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

        // Keep the UI range at 1p-150p, but use a codec-safe minimum output
        // height for video. Tiny frames are rejected by many H.264 encoders.
        // Presentation preserves the source video's aspect ratio.
        val outputHeight = targetHeightP.coerceIn(32, 150).let {
            if (it % 2 == 0) it else it - 1
        }
        val scaledMp4 = File(context.cacheDir, "scaled_${System.currentTimeMillis()}.mp4")
        try {
            transcodeToHeight(context, input, scaledMp4, outputHeight, targetFps.coerceIn(5, 30))
            if (outputFormat == "MP4") {
                scaledMp4.copyTo(File(outputPath), overwrite = true)
            } else {
                remuxTo3gp(scaledMp4, File(outputPath))
            }
        } finally {
            scaledMp4.delete()
        }
        require(File(outputPath).exists() && File(outputPath).length() > 0L) {
            "File hasil 3GP kosong"
        }
    }

    private fun transcodeToHeight(context: Context, input: File, output: File, height: Int, fps: Int) {
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Exception?>(null)
        val mainHandler = Handler(Looper.getMainLooper())

        mainHandler.post {
            try {
                val effects = Effects(
                    emptyList(),
                    listOf(
                        FrameDropEffect.createDefaultFrameDropEffect(fps.toFloat()),
                        Presentation.createForHeight(height)
                    )
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

    private fun remuxTo3gp(input: File, output: File) {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            extractor.setDataSource(input.absolutePath)
            val trackMap = mutableMapOf<Int, Int>()
            var hasVideo = false
            var maxBuffer = 1024 * 1024

            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                val supported = mime == MediaFormat.MIMETYPE_VIDEO_AVC ||
                    mime == MediaFormat.MIMETYPE_VIDEO_H263 ||
                    mime == MediaFormat.MIMETYPE_VIDEO_MPEG4 ||
                    mime == MediaFormat.MIMETYPE_AUDIO_AAC ||
                    mime == MediaFormat.MIMETYPE_AUDIO_AMR_NB ||
                    mime == MediaFormat.MIMETYPE_AUDIO_AMR_WB
                if (!supported) continue

                if (muxer == null) {
                    muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP)
                }
                val outTrack = muxer!!.addTrack(format)
                trackMap[i] = outTrack
                if (mime.startsWith("video/")) hasVideo = true
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    maxBuffer = maxOf(maxBuffer, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
            }

            require(hasVideo && trackMap.isNotEmpty()) {
                "Format video dari kamera tidak didukung oleh kontainer 3GP"
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
                    throw IllegalStateException("Gagal menyelesaikan file 3GP", e)
                }
            }
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            if (!started) output.delete()
        }
    }
}
