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
        pixelEnabled: Boolean = true,
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

        var durationUs = (retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION
        )?.toLongOrNull() ?: 0L) * 1000L

        // Fix "gagal memuat video: null": kalau retriever gagal baca durasi
        // (hasilnya 0), ambil durasi dari track mentah via MediaExtractor,
        // dan kalau tetap gagal, pakai fallback 3 detik supaya video tidak
        // kosong total (0 frame = file dianggap korup oleh pemutar video).
        if (durationUs <= 0L) {
            durationUs = readDurationFromExtractor(inputPath) ?: 3_000_000L
        }

        val srcWidth = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
        )?.toIntOrNull() ?: 480
        val srcHeight = retriever.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
        )?.toIntOrNull() ?: 640

        var outWidth: Int
        var outHeight: Int
        if (pixelEnabled) {
            val scale = targetHeightP.toFloat() / srcHeight.toFloat()
            outWidth = (srcWidth * scale).toInt()
            outHeight = targetHeightP
        } else {
            outWidth = srcWidth
            outHeight = srcHeight
        }
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

        // Fix "gagal memuat video: null": pakai container MPEG_4 (bukan
        // MUXER_OUTPUT_3GPP) karena jauh lebih kompatibel di berbagai HP saat
        // isinya H.264 -- 3GP sendiri sebenarnya "turunan" dari struktur MP4,
        // jadi file ini tetap valid meski ekstensinya .3gp.
        val muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
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
        var t = 0L
        var framesWritten = 0

        while (t < durationUs) {
            val frameStart = SystemClock.elapsedRealtime()
            val rawFrame = retriever.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST)
            if (rawFrame != null) {
                val processed = LoFiProcessor.process(
                    rawFrame, targetHeightP,
                    pixelEnabled = pixelEnabled,
                    filter = filter,
                    noiseEnabled = noiseEnabled,
                    stampTimestamp = dateMarkEnabled
                )
                val canvas = inputSurface.lockCanvas(null)
                try {
                    canvas.drawBitmap(processed, null, Rect(0, 0, outWidth, outHeight), null)
                } finally {
                    inputSurface.unlockCanvasAndPost(canvas)
                }
                framesWritten++
            }
            drain(false)
            val elapsed = SystemClock.elapsedRealtime() - frameStart
            val sleepTime = frameIntervalMs - elapsed
            if (sleepTime > 0) SystemClock.sleep(sleepTime)
            t += frameIntervalUs
        }

        // Fix "gagal memuat video: null": kalau karena suatu hal nggak ada
        // satupun frame berhasil diambil, paksa minimal 1 frame hitam supaya
        // file tidak kosong total.
        if (framesWritten == 0) {
            val canvas = inputSurface.lockCanvas(null)
            try {
                canvas.drawColor(android.graphics.Color.BLACK)
            } finally {
                inputSurface.unlockCanvasAndPost(canvas)
            }
            drain(false)
        }

        drain(true)

        retriever.release()
        encoder.stop()
        encoder.release()
        inputSurface.release()
        if (muxerStarted) muxer.stop()
        muxer.release()
    }

    private fun readDurationFromExtractor(path: String): Long? {
        return try {
            val extractor = MediaExtractor()
            extractor.setDataSource(path)
            var maxDurationUs = 0L
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
                    maxDurationUs = max(maxDurationUs, fmt.getLong(MediaFormat.KEY_DURATION))
                }
            }
            extractor.release()
            if (maxDurationUs > 0L) maxDurationUs else null
        } catch (e: Exception) {
            null
        }
    }

    // ---------- Audio: AAC -> PCM mono -> resample 8kHz -> hancurkan -> AMR-NB ----------

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
        val destroyed = degradeAudio(resampled)
        return encodeToAmrNb(destroyed)
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

    private fun degradeAudio(pcm: ShortArray): ShortArray {
        if (pcm.isEmpty()) return pcm
        val rnd = Random(System.nanoTime())

        val muffled = ShortArray(pcm.size)
        var i = 0
        while (i < pcm.size) {
            val v = pcm[i]
            muffled[i] = v
            if (i + 1 < pcm.size) muffled[i + 1] = v
            i += 2
        }

        val levels = 20
        val step = (65536 / levels).coerceAtLeast(1)

        return ShortArray(muffled.size) { idx ->
            var v = muffled[idx].toInt()
            v = (v * 3.2).toInt()
            v = v.coerceIn(-32768, 32767)
            v = (v / step) * step
            v += rnd.nextInt(-500, 500)
            v.coerceIn(-32768, 32767).toShort()
        }
    }

    private fun encodeToAmrNb(pcm: ShortArray): Pair<MediaFormat, List<EncodedSample>> {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AMR_NB, 8000, 1)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 4750)

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AMR_NB)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val frameSamples = 160
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
}orts.size / channels
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

    private fun degradeAudio(pcm: ShortArray): ShortArray {
        if (pcm.isEmpty()) return pcm
        val rnd = Random(System.nanoTime())

        val muffled = ShortArray(pcm.size)
        var i = 0
        while (i < pcm.size) {
            val v = pcm[i]
            muffled[i] = v
            if (i + 1 < pcm.size) muffled[i + 1] = v
            i += 2
        }

        val levels = 20
        val step = (65536 / levels).coerceAtLeast(1)

        return ShortArray(muffled.size) { idx ->
            var v = muffled[idx].toInt()
            v = (v * 3.2).toInt()
            v = v.coerceIn(-32768, 32767)
            v = (v / step) * step
            v += rnd.nextInt(-500, 500)
            v.coerceIn(-32768, 32767).toShort()
        }
    }

    private fun encodeToAmrNb(pcm: ShortArray): Pair<MediaFormat, List<EncodedSample>> {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AMR_NB, 8000, 1)
        format.setInteger(MediaFormat.KEY_BIT_RATE, 4750)

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AMR_NB)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoder.start()

        val frameSamples = 160
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
