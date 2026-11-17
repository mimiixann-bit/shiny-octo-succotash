package com.lofi.cam

import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.random.Random

object VideoLoFiProcessor {

    private data class EncodedSample(val data: ByteArray, val presentationTimeUs: Long, val flags: Int)

    fun convert(
        inputPath: String,
        outputPath: String,
        targetHeightP: Int,
        targetFps: Int,
        filter: LoFiProcessor.FilterType = LoFiProcessor.FilterType.NORMAL,
        qualityPercent: Int = 50,
        noiseEnabled: Boolean = true,
        dateMarkEnabled: Boolean = true
    ) {
        val audioResult = try {
            decodeAndEncodeAudio(inputPath)
        } catch (e: Exception) {
            null
        }

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
        if (outWidth % 2 != 0) outWidth += 1
        if (outHeight % 2 != 0) outHeight += 1
        outWidth = max(16, outWidth)
        outHeight = max(16, outHeight)

        val bitRate = (50_000 + (qualityPercent.coerceIn(10, 100) / 100.0 * 450_000)).toInt()

        val videoFormat = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, outWidth, outHeight
        ).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, targetFps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val inputSurface = encoder.createInputSurface()
        encoder.start()

        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP)
        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var muxerStarted = false
        val bufferInfo = MediaCodec.BufferInfo()

        if (audioResult != null) {
            audioTrackIndex = muxer.addTrack(audioResult.first)
        }

        fun writeBufferedAudio() {
            if (audioResult == null) return
            for (sample in audioResult.second) {
                if (sample.data.isEmpty()) continue
                val bb = ByteBuffer.wrap(sample.data)
                val info = MediaCodec.BufferInfo()
                info.set(
                    0,
                    sample.data.size,
                    sample.presentationTimeUs,
                    sample.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
                )
                muxer.writeSampleData(audioTrackIndex, bb, info)
            }
        }

        fun drain(endOfStream: Boolean) {
            if (endOfStream) encoder.signalEndOfInputStream()
            while (true) {
                val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
                when {
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
