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

    private const val TARGET_MEGAPIXELS = 0.3f
    private const val PIXELATE_BLOCK = 4

    fun process(source: Bitmap, stampTimestamp: Boolean = true): Bitmap {
        val lowRes = downscaleToMegapixels(source, TARGET_MEGAPIXELS)
        val pixelated = pixelate(lowRes, PIXELATE_BLOCK)
        val graded = colorGrade(pixelated)
        val grainy = addGrain(graded, amount = 18)
        val vignetted = addVignette(grainy)
        return if (stampTimestamp) stampDate(vignetted) else vignetted
    }

    private fun downscaleToMegapixels(src: Bitmap, megapixels: Float): Bitmap {
        val targetPixels = megapixels * 1_000_000f
        val scale = kotlin.math.sqrt(targetPixels / (src.width * src.height))
        val w = max(32, (src.width * scale).toInt())
        val h = max(24, (src.height * scale).toInt())
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun pixelate(src: Bitmap, block: Int): Bitmap {
        val smallW = max(1, src.width / block)
        val smallH = max(1, src.height / block)
        val shrunk = Bitmap.createScaledBitmap(src, smallW, smallH, true)
        return Bitmap.createScaledBitmap(shrunk, src.width, src.height, false)
    }

    private fun colorGrade(src: Bitmap): Bitmap {
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
