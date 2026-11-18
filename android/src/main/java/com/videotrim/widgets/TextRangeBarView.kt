package com.videotrim.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A range bar for the selected text item. The bar spans the current trim range (read live through
 * [rangeProvider]); the highlighted segment is the item's start..end, with the item's text shown
 * inside it.
 */
class TextRangeBarView(context: Context) : View(context) {

    companion object {
        const val MIN_DURATION_MS = 300L
    }

    var item: TextItem? = null
        set(value) {
            field = value
            invalidate()
        }

    var rangeProvider: (() -> Pair<Long, Long>)? = null
    var onChanging: (() -> Unit)? = null
    var onChanged: (() -> Unit)? = null
    var accentColor: Int = Color.parseColor("#FFD60A")

    private var currentMs = 0L

    private enum class Mode {
        LEFT,
        RIGHT,
        MOVE,
    }

    private var mode: Mode? = null
    private var downX = 0f
    private var initStart = 0L
    private var initEnd = 0L
    private var changed = false

    private val handleW = dp(14f)
    private val labelH = dp(16f)
    private val grabSlop = dp(14f)
    private val corner = dp(6f)

    private val trackPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 255, 255, 255) }
    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) }
    private val gripPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            strokeWidth = dp(2f)
            strokeCap = Paint.Cap.ROUND
        }
    private val playheadPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = dp(2f)
        }
    private val labelPaint =
        android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize =
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
    private val timesPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 255, 255, 255)
            textSize =
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        }

    fun setCurrentMs(ms: Long) {
        currentMs = ms
        invalidate()
    }

    private fun xFor(ms: Long, rs: Long, re: Long): Float =
        (ms - rs).toFloat() / (re - rs).toFloat() * width

    private fun clampTo(v: Long, lo: Long, hi: Long): Long =
        if (hi < lo) lo else min(max(v, lo), hi)

    private fun fmt(ms: Long): String {
        val tenths = (ms / 100) % 10
        val totalSec = ms / 1000
        return "%d:%02d.%d".format(totalSec / 60, totalSec % 60, tenths)
    }

    override fun onDraw(canvas: Canvas) {
        val it = item ?: return
        val (rs, re) = rangeProvider?.invoke() ?: return
        if (re <= rs || width == 0) return

        val s = it.startMs.coerceIn(rs, re)
        val e = it.endMs.coerceIn(rs, re)
        val top = labelH
        val bottom = height - dp(2f)

        canvas.drawText(
            "${fmt(s - rs)} - ${fmt(e - rs)}   (${fmt(e - s)})",
            0f,
            labelH - dp(4f),
            timesPaint,
        )
        canvas.drawRoundRect(RectF(0f, top, width.toFloat(), bottom), corner, corner, trackPaint)

        val xl = xFor(s, rs, re)
        val xr = max(xFor(e, rs, re), xl + dp(4f))
        val hw = min(handleW, (xr - xl) / 2f)

        segPaint.color = accentColor
        canvas.drawRoundRect(RectF(xl, top, xr, bottom), corner, corner, segPaint)
        canvas.drawRect(xl, top, xl + hw, bottom, handlePaint)
        canvas.drawRect(xr - hw, top, xr, bottom, handlePaint)
        val midY = (top + bottom) / 2f
        val gh = (bottom - top) * 0.22f
        canvas.drawLine(xl + hw / 2f, midY - gh, xl + hw / 2f, midY + gh, gripPaint)
        canvas.drawLine(xr - hw / 2f, midY - gh, xr - hw / 2f, midY + gh, gripPaint)

        val pad = dp(6f)
        val avail = (xr - hw - pad) - (xl + hw + pad)
        if (avail > dp(10f)) {
            val label =
                TextUtils.ellipsize(it.text, labelPaint, avail, TextUtils.TruncateAt.END).toString()
            val baseline = midY - (labelPaint.ascent() + labelPaint.descent()) / 2f
            canvas.drawText(label, xl + hw + pad, baseline, labelPaint)
        }

        if (currentMs in rs..re) {
            val px = xFor(currentMs, rs, re)
            canvas.drawLine(px, top - dp(2f), px, bottom, playheadPaint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val it = item ?: return false
        val (rs, re) = rangeProvider?.invoke() ?: return false
        if (re <= rs || width == 0) return false

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Pull the item back inside the trim range if the trim handles moved past it.
                var s = it.startMs.coerceIn(rs, re)
                var en = it.endMs.coerceIn(rs, re)
                if (en - s < MIN_DURATION_MS) {
                    en = min(re, s + MIN_DURATION_MS)
                    s = max(rs, en - MIN_DURATION_MS)
                }
                it.startMs = s
                it.endMs = en

                val xl = xFor(s, rs, re)
                val xr = xFor(en, rs, re)
                val nearL = e.x >= xl - grabSlop && e.x <= xl + handleW
                val nearR = e.x >= xr - handleW && e.x <= xr + grabSlop
                mode =
                    when {
                        nearL && nearR ->
                            if (abs(e.x - xl) <= abs(e.x - xr)) Mode.LEFT else Mode.RIGHT
                        nearL -> Mode.LEFT
                        nearR -> Mode.RIGHT
                        e.x in xl..xr -> Mode.MOVE
                        else -> return false
                    }
                downX = e.x
                initStart = s
                initEnd = en
                changed = false
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val m = mode ?: return false
                val dt = ((e.x - downX) / width * (re - rs)).toLong()
                when (m) {
                    Mode.LEFT ->
                        it.startMs =
                            clampTo(initStart + dt, rs, max(rs, it.endMs - MIN_DURATION_MS))
                    Mode.RIGHT ->
                        it.endMs = clampTo(initEnd + dt, min(re, it.startMs + MIN_DURATION_MS), re)
                    Mode.MOVE -> {
                        val dur = initEnd - initStart
                        val ns = clampTo(initStart + dt, rs, re - dur)
                        it.startMs = ns
                        it.endMs = ns + dur
                    }
                }
                changed = true
                invalidate()
                onChanging?.invoke()
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                mode = null
                if (changed) onChanged?.invoke()
                changed = false
                return true
            }
        }
        return false
    }

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
