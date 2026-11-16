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

object VideoLoFiProcessor {

    private data class EncodedSample(val data: ByteArray, val presentationTimeUs: Long, val flags: Int)

    fun convert(
        inputPath: String,
        outputPath: String,
        targetHeightP: Int,
        targetFps: Int
    ) {
        // 1) Proses audio dulu (kalau ada): AAC -> PCM mono -> resample 8kHz -> AMR-NB
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

        val videoFormat = MediaFormat.createVideoFormat(
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
                        if (!endOfStream) return
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        videoTrackIndex = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                        writeBufferedAudio()
                    }
                    outIndex >= 0 -> {
                        val encodedData = encoder.getOutputBuffer(outIndex)
                        if (encodedData != null && bufferInfo.size > 0 && muxerStarted) {
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
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

    // ---------- Audio: AAC -> PCM mono -> resample 8kHz -> AMR-NB ----------

    private fun decodeAndEncodeAudio(inputPath: String): Pair<MediaFormat, List<EncodedSample>>? {
        val extractor = MediaExtractor()
        extractor.setDataSource(inputPath)

        var audioTrackIndex = -1
        var inputFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val fmt = extractor.getTrackFormat(i)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                inputFormat = fmt
                break
            }
        }
        if (audioTrackIndex < 0 || inputFormat == null) {
            extractor.release()
            return null
        }
        extractor.selectTrack(audioTrackIndex)

        val srcSampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val srcChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val mime = inputFormat.getString(MediaFormat.KEY_MIME)!!

        val decoder = MediaCodec.createDecoderByType(mime)
        decoder.configure(inputFormat, null, null, 0)
        decoder.start()

        val pcmBuffer = ByteArrayOutputStream()
        val bufferInfo = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false

        while (!sawOutputEOS) {
            if (!sawInputEOS) {
                val inIndex = decoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inIndex)!!
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEOS = true
                    } else {
                        val pts = extractor.sampleTime
                        decoder.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                        extractor.advance()
                    }
                }
            }

            val outIndex = decoder.dequeueOutputBuffer(bufferInfo, 10_000)
            if (outIndex >= 0) {
                val outputBuffer = decoder.getOutputBuffer(outIndex)
                if (outputBuffer != null && bufferInfo.size > 0) {
                    val chunk = ByteArray(bufferInfo.size)
                    outputBuffer.position(bufferInfo.offset)
                    outputBuffer.get(chunk)
                    pcmBuffer.write(chunk)
                }
                decoder.releaseOutputBuffer(outIndex, false)
                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    sawOutputEOS = true
                }
            }
        }

        decoder.stop()
        decoder.release()
        extractor.release()

        val monoPcm = toMono16Bit(pcmBuffer.toByteArray(), srcChannels)
        val resampled = resampleTo8k(monoPcm, srcSampleRate)
        return encodeToAmrNb(resampled)
    }

    private fun toMono16Bit(pcm: ByteArray, channels: Int): ShortArray {
        val totalSamples = pcm.size / 2
        val shorts = ShortArray(totalSamples)
        val bb = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until totalSamples) shorts[i] = bb.short
        if (channels <= 1) return shorts

        val frames = shorts.size / channels
        val mono = ShortArray(frames)
        for (f in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += shorts[f * channels + c]
            mono[f] = (sum / channels).toShort()
        }
        return mono
    }

    private fun resampleTo8k(mono: ShortArray, srcRate: Int): ShortArray {
        if (srcRate == 8000 || mono.isEmpty()) return mono
        val ratio = srcRate.toDouble() / 8000.0
        val outLen = (mono.size / ratio).toInt()
        val out = ShortArray(outLen)
        for (i in 0 until outLen) {
            val srcIndex = (i * ratio).toInt().coerceIn(0, mono.size - 1)
            out[i] = mono[srcIndex]
        }
        return out
    }

    private fun encodeToAmrNb(pcm: ShortArray): Pair<MediaFormat, List<EncodedSample>> {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AMR_NB, 8000, 1)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 12200)

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AMR_NB)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val frameSamples = 160 // 20ms @ 8kHz
        var sampleIndex = 0
        var capturedFormat: MediaFormat? = null
        val samples = mutableListOf<EncodedSample>()
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false

        while (true) {
            if (!inputDone) {
                val inIndex = encoder.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val inputBuffer = encoder.getInputBuffer(inIndex)!!
                    inputBuffer.clear()
                    if (sampleIndex < pcm.size) {
                        val remaining = pcm.size - sampleIndex
                        val count = minOf(frameSamples, remaining)
                        for (i in 0 until count) inputBuffer.putShort(pcm[sampleIndex + i])
                        val ptsUs = (sampleIndex.toLong() * 1_000_000L) / 8000L
                        encoder.queueInputBuffer(inIndex, 0, count * 2, ptsUs, 0)
                        sampleIndex += count
                    } else {
                        val ptsUs = (sampleIndex.toLong() * 1_000_000L) / 8000L
                        encoder.queueInputBuffer(inIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    }
                }
            }

            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    capturedFormat = encoder.outputFormat
                }
                outIndex >= 0 -> {
                    val outputBuffer = encoder.getOutputBuffer(outIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        val data = ByteArray(bufferInfo.size)
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.get(data)
                        samples.add(EncodedSample(data, bufferInfo.presentationTimeUs, bufferInfo.flags))
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        encoder.stop()
                        encoder.release()
                        return Pair(capturedFormat ?: format, samples)
                    }
                }
            }
        }
    }
}
