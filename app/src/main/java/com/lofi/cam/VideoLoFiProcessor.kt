package com.lofi.cam

import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.SystemClock
import kotlin.math.max

/**
 * Mengubah video MP4 hasil rekaman CameraX menjadi video "0.3MP burik" ala
 * kamera jadul: resolusi rendah, blur halus, grain, vignette, lalu dibungkus
 * ulang ke container .3GP (video-only, tanpa audio).
 *
 * Pipeline: MediaMetadataRetriever (ambil frame per-waktu) -> LoFiProcessor
 * (efek burik yang sama dengan foto) -> MediaCodec encoder (Surface input)
 * -> MediaMuxer (MUXER_OUTPUT_3GPP).
 */
object VideoLoFiProcessor {

    fun convert(
        inputPath: String,
        outputPath: String,
        targetHeightP: Int,
        targetFps: Int
    ) {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(inputPath)

        val durationMs = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION
        )?.toLongOrNull() ?: 0L
        val srcWidth = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
        )?.toIntOrNull() ?: 480
        val srcHeight = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
        )?.toIntOrNull() ?: 640

        val scale = targetHeightP.toFloat() / srcHeight.toFloat()
        var outWidth = (srcWidth * scale).toInt()
        var outHeight = targetHeightP
        // H.264 butuh lebar & tinggi genap (kelipatan 2)
        if (outWidth % 2 != 0) outWidth += 1
        if (outHeight % 2 != 0) outHeight += 1
        outWidth = max(16, outWidth)
        outHeight = max(16, outHeight)

        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, outWidth, outHeight
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, 300_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP)
        var trackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()

        fun drain(endOfStream: Boolean) {
            if (endOfStream) encoder.signalEndOfInputStream()
            while (true) {
                val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) return
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        trackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outIndex >= 0 -> {
                        val encodedData = encoder.getOutputBuffer(outIndex)
                        if (encodedData != null && bufferInfo.size > 0 && muxerStarted) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
                        }
                        encoder.releaseOutputBuffer(outIndex, false)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            return
                        }
                    }
                }
            }
        }

        val frameIntervalUs = 1_000_000L / targetFps
        val frameIntervalMs = 1000L / targetFps
        val durationUs = durationMs * 1000L
        var t = 0L

        while (t < durationUs) {
            val frameStart = SystemClock.elapsedRealtime()
            val rawFrame = retriever.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)
            if (rawFrame != null) {
                val processed = LoFiProcessor.process(rawFrame, targetHeightP, stampTimestamp = false)
                val canvas = inputSurface.lockCanvas(null)
                try {
                    canvas.drawBitmap(processed, null, Rect(0, 0, outWidth, outHeight), null)
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }
            }
            drain(false)
            val elapsed = SystemClock.elapsedRealtime() - frameStart
            val sleepTime = frameIntervalMs - elapsed
            if (sleepTime > 0) SystemClock.sleep(sleepTime)
            t += frameIntervalUs
        }

        drain(true)

        retriever.release()
        encoder.stop()
        encoder.release()
        inputSurface.release()
        if (muxerStarted) muxer.stop()
        muxer.release()
    }
}
