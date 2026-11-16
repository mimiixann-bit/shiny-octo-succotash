package com.lofi.cam

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Lightweight MP4-to-3GP remux. Avoids the previous per-frame bitmap/codec
 * pipeline, including lockCanvas() on a MediaCodec input surface, which is
 * unsupported on many devices and was the source of failed processing.
 */
object VideoLoFiProcessor {
    fun convert(inputPath: String, outputPath: String, targetHeightP: Int, targetFps: Int) {
        val input = File(inputPath)
        require(input.exists() && input.length() > 0L) { "File rekaman kosong atau tidak ditemukan" }

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
                    muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP)
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

            require(File(outputPath).exists() && File(outputPath).length() > 0L) {
                "File hasil 3GP kosong"
            }
        } finally {
            if (started) try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
            if (!started) File(outputPath).delete()
        }
    }
}