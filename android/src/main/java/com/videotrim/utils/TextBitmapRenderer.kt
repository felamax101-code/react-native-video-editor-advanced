package com.videotrim.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.videotrim.widgets.TextItem
import com.videotrim.widgets.TextOverlayView
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

object TextBitmapRenderer {

    private const val MAX_DIM = 4096

    /** Draws [item] into a transparent PNG at export resolution. Returns false on failure. */
    fun render(item: TextItem, frameHeightPx: Int, outFile: File): Boolean {
        if (item.text.isBlank() || frameHeightPx <= 0) return false

        val textSize = TextOverlayView.BASE_HEIGHT_FRACTION * frameHeightPx * item.scale
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                this.textSize = textSize
                color = item.color
                textAlign = Paint.Align.CENTER
                // Same shadow as the preview, scaled with the text size.
                setShadowLayer(textSize * 0.06f, 0f, textSize * 0.03f, Color.argb(160, 0, 0, 0))
            }

        val pad = textSize * 0.2f
        val w0 = paint.measureText(item.text) + 2 * pad
        val h0 = (paint.descent() - paint.ascent()) + 2 * pad

        val rad = Math.toRadians(item.rotationDeg.toDouble())
        val c = abs(cos(rad)).toFloat()
        val s = abs(sin(rad)).toFloat()
        val bw = w0 * c + h0 * s
        val bh = w0 * s + h0 * c

        // Safety cap for extreme zoom on very large videos.
        val k = minOf(1f, MAX_DIM / maxOf(bw, bh))
        val pxW = ceil(bw * k).toInt().coerceAtLeast(2)
        val pxH = ceil(bh * k).toInt().coerceAtLeast(2)

        return try {
            val bmp = Bitmap.createBitmap(pxW, pxH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.scale(k, k)
            canvas.translate(bw / 2f, bh / 2f)
            canvas.rotate(item.rotationDeg)
            val baseline = -(paint.ascent() + paint.descent()) / 2f
            canvas.drawText(item.text, 0f, baseline, paint)
            FileOutputStream(outFile).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()
            true
        } catch (t: Throwable) {
            t.printStackTrace()
            false
        }
    }
}
