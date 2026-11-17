package com.lofi.cam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.random.Random

object LoFiProcessor {

    enum class FilterType { NORMAL, SEPIA, BW, WARM, COOL }

    fun process(
        source: Bitmap,
        targetHeightP: Int,
        pixelEnabled: Boolean = true,
        filter: FilterType = FilterType.NORMAL,
        noiseEnabled: Boolean = true,
        stampTimestamp: Boolean = true
    ): Bitmap {
        val base = if (pixelEnabled) {
            val lowRes = downscaleToHeight(source, targetHeightP)
            Bitmap.createScaledBitmap(lowRes, source.width, source.height, true)
        } else {
            source
        }
        val graded = colorGrade(base, filter)
        val grainy = if (noiseEnabled) addGrain(graded, amount = 18) else graded
        val vignetted = addVignette(grainy)
        return if (stampTimestamp) stampDate(vignetted) else vignetted
    }

    private fun downscaleToHeight(src: Bitmap, targetHeightP: Int): Bitmap {
        val h = max(1, targetHeightP)
        val scale = h.toFloat() / src.height.toFloat()
        val w = max(1, (src.width * scale).toInt())
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun colorGrade(src: Bitmap, filter: FilterType): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val w = out.width
        val h = out.height
        val pixels = IntArray(w * h)
        out.getPixels(pixels, 0, w, 0, 0, w, h)

        for (i in pixels.indices) {
            val p = pixels[i]
            var r = Color.red(p)
            var g = Color.green(p)
            var b = Color.blue(p)

            r = contrast(r, 1.15f)
            g = contrast(g, 1.12f)
            b = contrast(b, 1.08f)

            when (filter) {
                FilterType.NORMAL -> { }
                FilterType.SEPIA -> {
                    val nr = (r * 0.393 + g * 0.769 + b * 0.189)
                    val ng = (r * 0.349 + g * 0.686 + b * 0.168)
                    val nb = (r * 0.272 + g * 0.534 + b * 0.131)
                    r = nr.toInt().coerceIn(0, 255)
                    g = ng.toInt().coerceIn(0, 255)
                    b = nb.toInt().coerceIn(0, 255)
                }
                FilterType.BW -> {
                    val gray = (r * 0.299 + g * 0.587 + b * 0.114).toInt().coerceIn(0, 255)
                    r = gray; g = gray; b = gray
                }
                FilterType.WARM -> {
                    r = (r + 20).coerceIn(0, 255)
                    g = (g + 8).coerceIn(0, 255)
                    b = (b - 20).coerceIn(0, 255)
                }
                FilterType.COOL -> {
                    r = (r - 15).coerceIn(0, 255)
                    b = (b + 20).coerceIn(0, 255)
                }
            }

            pixels[i] = Color.rgb(r, g, b)
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun contrast(channel: Int, factor: Float): Int {
        val v = ((channel - 128) * factor + 128).toInt()
        return v.coerceIn(0, 255)
    }

    private fun addGrain(src: Bitmap, amount: Int): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val w = out.width
        val h = out.height
        val pixels = IntArray(w * h)
        out.getPixels(pixels, 0, w, 0, 0, w, h)
        val rnd = Random(System.nanoTime())

        for (i in pixels.indices) {
            val noise = rnd.nextInt(-amount, amount + 1)
            val p = pixels[i]
            val r = (Color.red(p) + noise).coerceIn(0, 255)
            val g = (Color.green(p) + noise).coerceIn(0, 255)
            val b = (Color.blue(p) + noise).coerceIn(0, 255)
            pixels[i] = Color.rgb(r, g, b)
        }
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun addVignette(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val radius = max(out.width, out.height) * 0.75f
        val paint = Paint().apply {
            shader = RadialGradient(
                out.width / 2f, out.height / 2f, radius,
                Color.argb(0, 0, 0, 0), Color.argb(130, 0, 0, 0),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, out.width.toFloat(), out.height.toFloat(), paint)
        return out
    }

    private fun stampDate(src: Bitmap): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val text = SimpleDateFormat("dd'/'MM'/'yyyy", Locale.getDefault()).format(Date())
        val textSize = out.height * 0.045f
        val paint = Paint().apply {
            color = Color.argb(220, 255, 165, 0)
            this.textSize = textSize
            isAntiAlias = false
            isFakeBoldText = true
        }
        val margin = out.width * 0.03f
        canvas.drawText(
            text,
            out.width - paint.measureText(text) - margin,
            out.height - margin,
            paint
        )
        return out
    }
}
