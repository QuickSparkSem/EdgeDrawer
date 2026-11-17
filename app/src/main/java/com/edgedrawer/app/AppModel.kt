package com.edgedrawer.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.text.Normalizer

enum class AppColorFilter(val label: String, val swatchColor: Int) {
    ALL("Tutti", Color.WHITE),
    RED("Rosso", Color.parseColor("#FF4B4B")),
    YELLOW("Giallo", Color.parseColor("#FFD23F")),
    GREEN("Verde", Color.parseColor("#2EE585")),
    BLUE("Blu", Color.parseColor("#3B9EFF")),
    PURPLE("Viola", Color.parseColor("#B36BFF")),
    MONO("Bianco/Nero", Color.parseColor("#9AA3B2"))
}

data class AppEntry(
    val label: String,
    val packageName: String,
    val iconBitmap: Bitmap,
    val firstLetter: Char,
    val installTime: Long,
    val colorCategory: AppColorFilter
)

object IconColorAnalyzer {

    fun normalizeFirstLetter(label: String): Char {
        val trimmed = label.trim()
        if (trimmed.isEmpty()) return '#'
        val normalized = Normalizer.normalize(trimmed.substring(0, 1), Normalizer.Form.NFD)
            .replace("\\p{InCombiningDiacriticalMarks}+".toRegex(), "")
            .uppercase()
        val ch = normalized.firstOrNull() ?: '#'
        return if (ch in 'A'..'Z') ch else '#'
    }

    fun drawableToBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            return Bitmap.createScaledBitmap(drawable.bitmap, sizePx, sizePx, true)
        }
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    fun detectDominantColor(bitmap: Bitmap): AppColorFilter {
        val scaled = Bitmap.createScaledBitmap(bitmap, 36, 36, true)
        val counts = mutableMapOf(
            AppColorFilter.RED to 0,
            AppColorFilter.YELLOW to 0,
            AppColorFilter.GREEN to 0,
            AppColorFilter.BLUE to 0,
            AppColorFilter.PURPLE to 0,
            AppColorFilter.MONO to 0
        )

        val hsv = FloatArray(3)
        for (x in 4 until 32) {
            for (y in 4 until 32) {
                val pixel = scaled.getPixel(x, y)
                val alpha = Color.alpha(pixel)
                if (alpha < 140) continue

                Color.colorToHSV(pixel, hsv)
                val hue = hsv[0]
                val sat = hsv[1]
                val value = hsv[2]

                if (sat < 0.22f || value < 0.18f) {
                    counts[AppColorFilter.MONO] = (counts[AppColorFilter.MONO] ?: 0) + 1
                } else {
                    val cat = when {
                        hue < 24f || hue >= 338f -> AppColorFilter.RED
                        hue < 68f -> AppColorFilter.YELLOW
                        hue < 168f -> AppColorFilter.GREEN
                        hue < 258f -> AppColorFilter.BLUE
                        else -> AppColorFilter.PURPLE
                    }
                    // Dai peso maggiore ai pixel colorati rispetto agli sfondi bianchi/neri
                    counts[cat] = (counts[cat] ?: 0) + 3
                }
            }
        }
        if (scaled != bitmap) scaled.recycle()

        val best = counts.maxByOrNull { it.value }
        return if (best == null || best.value == 0) AppColorFilter.MONO else best.key
    }
}
