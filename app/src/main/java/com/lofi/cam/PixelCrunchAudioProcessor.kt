package com.lofi.cam

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Pixel-linked lo-fi audio for PCM16 export.
 * Low pixel settings produce stronger bit-crushing, sample-hold and static.
 */
class PixelCrunchAudioProcessor(pixelHeight: Int) : AudioProcessor {
    private val pixel = if (pixelHeight <= 0) 150 else pixelHeight.coerceIn(1, 150)
    private val bitDepth = 2 + ((pixel - 1) * 10f / 149f).roundToInt()
    private val quantizationStep = 1 shl (16 - bitDepth)
    private val holdSamples = 1 + ((150 - pixel) / 12)
    private val noiseAmplitude = (quantizationStep * (0.72f - pixel / 260f).coerceAtLeast(0.08f))
        .toInt().coerceAtLeast(8)

    private var configuredFormat = AudioProcessor.AudioFormat.NOT_SET
    private var outputBuffer: ByteBuffer = AudioProcessor.EMPTY_BUFFER
    private var ended = false
    private var sampleCounter = 0
    private var heldSample = 0
    private var randomState = 0x13579BDF

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        configuredFormat = if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
        return configuredFormat
    }

    override fun isActive(): Boolean = configuredFormat != AudioProcessor.AudioFormat.NOT_SET

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!isActive() || !inputBuffer.hasRemaining()) return
        val input = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val output = ByteBuffer.allocateDirect(input.remaining()).order(ByteOrder.LITTLE_ENDIAN)
        while (input.remaining() >= 2) {
            val original = input.short.toInt()
            if (sampleCounter % holdSamples == 0) {
                heldSample = (original / quantizationStep) * quantizationStep
            }
            randomState = randomState xor (randomState shl 13)
            randomState = randomState xor (randomState ushr 17)
            randomState = randomState xor (randomState shl 5)
            val noise = ((randomState and 0x7fffffff) % (noiseAmplitude * 2 + 1)) - noiseAmplitude
            val processed = (heldSample + noise).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            output.putShort(processed.toShort())
            sampleCounter++
        }
        output.flip()
        outputBuffer = output
    }

    override fun getOutput(): ByteBuffer {
        val result = outputBuffer
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        return result
    }

    override fun queueEndOfStream() {
        ended = true
    }

    override fun isEnded(): Boolean = ended && !outputBuffer.hasRemaining()

    override fun flush() {
        outputBuffer = AudioProcessor.EMPTY_BUFFER
        ended = false
        sampleCounter = 0
        heldSample = 0
        randomState = 0x13579BDF
    }

    override fun reset() {
        flush()
        configuredFormat = AudioProcessor.AudioFormat.NOT_SET
    }
}
