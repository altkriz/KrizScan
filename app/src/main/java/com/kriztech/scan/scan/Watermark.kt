package com.kriztech.scan.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import kotlin.math.max

enum class WatermarkPosition(val label: String) {
    DIAGONAL("Diagonal Center"),
    HEADER("Top Header"),
    FOOTER("Bottom Footer")
}

data class Watermark(
    val text: String,
    val position: WatermarkPosition = WatermarkPosition.DIAGONAL,
    val colorHex: String = "#808080",
    val alpha: Float = 0.35f
)

object WatermarkUtil {

    /**
     * Renders [watermark] cleanly onto a copy of [src].
     * Returns [src] unchanged if [watermark] is null or text is blank.
     */
    fun apply(src: Bitmap, watermark: Watermark?): Bitmap {
        if (watermark == null || watermark.text.isBlank()) return src

        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()

        val baseColor = runCatching { Color.parseColor(watermark.colorHex) }.getOrDefault(Color.DKGRAY)
        val alphaInt = (watermark.alpha.coerceIn(0.05f, 1.0f) * 255).toInt()

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = baseColor
            alpha = alphaInt
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        when (watermark.position) {
            WatermarkPosition.DIAGONAL -> {
                // Adaptive font sizing based on page diagonal and text length
                val diagonal = Math.hypot(w.toDouble(), h.toDouble()).toFloat()
                val targetTextWidth = diagonal * 0.65f
                paint.textSize = 100f
                val bounds = Rect()
                paint.getTextBounds(watermark.text, 0, watermark.text.length, bounds)
                val currentWidth = max(1, bounds.width())
                val scaledSize = (100f * (targetTextWidth / currentWidth)).coerceIn(24f, max(w, h) * 0.12f)
                paint.textSize = scaledSize

                canvas.save()
                canvas.translate(w / 2f, h / 2f)
                canvas.rotate(-40f)

                // Draw semi-transparent watermark text
                val fontMetrics = paint.fontMetrics
                val textY = -(fontMetrics.ascent + fontMetrics.descent) / 2f
                canvas.drawText(watermark.text, 0f, textY, paint)
                canvas.restore()
            }
            WatermarkPosition.HEADER -> {
                paint.textSize = (minOf(w, h) * 0.04f).coerceIn(20f, 72f)
                val topMargin = h * 0.05f
                val fontMetrics = paint.fontMetrics
                val textY = topMargin - fontMetrics.ascent
                canvas.drawText(watermark.text, w / 2f, textY, paint)
            }
            WatermarkPosition.FOOTER -> {
                paint.textSize = (minOf(w, h) * 0.035f).coerceIn(18f, 64f)
                val bottomMargin = h * 0.05f
                val fontMetrics = paint.fontMetrics
                val textY = h - bottomMargin - fontMetrics.descent
                canvas.drawText(watermark.text, w / 2f, textY, paint)
            }
        }

        return out
    }
}
