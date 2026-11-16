package com.lofi.cam

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pixel-linked lo-fi audio: lower video pixel settings produce stronger
 * bit-crushing, sample-hold artifacts and controlled static-like crackle.
 */
class PixelCrunchAudioProcessor(pixelHeight: Int) : BaseAudioProcessor() {
    private val pixel = if (pixelHeight <= 0) 150 else pixelHeight.coerceIn(1, 150)
    private val bitDepth = 2 + ((pixel - 1) * 10f / 149f).roundToInt()
    private val quantizationStep = 1 shl (16 - bitDepth)
    private val holdSamples = 1 + ((150 - pixel) / 12)
    private val noiseAmplitude = max(8, quantizationStep * (0.72f - pixel / 260f).coerceAtLeast(0.08f)).toInt()
    private var sampleCounter = 0
    private var heldSample = 0
    private var randomState = 0x13579BDF

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        return if (inputAudioFormat.encoding == C.ENCODING_PCM_16BIT) {
            inputAudioFormat
        } else {
            AudioProcessor.AudioFormat.NOT_SET
        }
    }

    override fun onQueueInput(inputBuffer: ByteBuffer) {
        val input = inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val output = replaceOutputBuffer(input.remaining()).order(ByteOrder.LITTLE_ENDIAN)
        while (input.remaining() >= 2) {
            val original = input.short.toInt()
            if (sampleCounter % holdSamples == 0) {
                val quantized = (original / quantizationStep) * quantizationStep
                heldSample = quantized
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
    }
}
