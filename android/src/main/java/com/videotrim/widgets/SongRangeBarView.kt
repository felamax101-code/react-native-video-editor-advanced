package com.videotrim.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * One song. [srcStartMs]..[srcEndMs] is the used part of the file;
 * [videoStartMs] is where that part begins in the video (absolute source time).
 */
class SongItem(
  val id: Long,
  val path: String,
  val title: String,
  val durationMs: Long,
  var srcStartMs: Long = 0L,
  var srcEndMs: Long = durationMs,
  var videoStartMs: Long = 0L,
  var volume: Float = 1f
) {
  val videoEndMs: Long get() = videoStartMs + (srcEndMs - srcStartMs)
}

data class SongState(
  val id: Long, val path: String, val title: String, val durationMs: Long,
  val srcStartMs: Long, val srcEndMs: Long, val videoStartMs: Long, val volume: Float
)

fun SongItem.toState() =
  SongState(id, path, title, durationMs, srcStartMs, srcEndMs, videoStartMs, volume)

class SongRangeBarView(context: Context) : View(context) {

  companion object {
    const val MIN_MS = 500L
    val ACCENT: Int = Color.parseColor("#34C7A0")
  }

  var item: SongItem? = null
    set(value) {
      field = value
      invalidate()
    }
  var rangeProvider: (() -> Pair<Long, Long>)? = null
  var othersProvider: ((Long) -> List<SongItem>)? = null
  var onChanging: (() -> Unit)? = null
  var onChanged: (() -> Unit)? = null

  private var currentMs = 0L

  private enum class Mode { LEFT, RIGHT, MOVE }
  private var mode: Mode? = null
  private var downX = 0f
  private var iVideoStart = 0L
  private var iSrcStart = 0L
  private var iSrcEnd = 0L
  private var lo = 0L
  private var hi = 0L
  private var changed = false

  private val handleW = dp(14f)
  private val labelH = dp(16f)
  private val grabSlop = dp(14f)
  private val corner = dp(6f)

  private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 255, 255, 255) }
  private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT }
  private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 0, 0, 0) }
  private val gripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.BLACK; strokeWidth = dp(2f); strokeCap = Paint.Cap.ROUND
  }
  private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.WHITE; strokeWidth = dp(2f)
  }
  private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.BLACK
    textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
    typeface = android.graphics.Typeface.DEFAULT_BOLD
  }
  private val timesPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.argb(200, 255, 255, 255)
    textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
  }

  fun setCurrentMs(ms: Long) { currentMs = ms; invalidate() }

  private fun xFor(ms: Long, rs: Long, re: Long): Float =
    (ms - rs).toFloat() / (re - rs).toFloat() * width

  private fun clampTo(v: Long, a: Long, b: Long): Long = if (b < a) a else min(max(v, a), b)

  private fun fmt(ms: Long): String {
    val m = max(0L, ms)
    return "%d:%02d.%d".format(m / 60000, (m / 1000) % 60, (m / 100) % 10)
  }

  override fun onDraw(canvas: Canvas) {
    val s = item ?: return
    val (rs, re) = rangeProvider?.invoke() ?: return
    if (re <= rs || width == 0) return

    val vs = s.videoStartMs.coerceIn(rs, re)
    val ve = s.videoEndMs.coerceIn(rs, re)
    val top = labelH
    val bottom = height - dp(2f)

    canvas.drawText(
      "Video ${fmt(vs - rs)} - ${fmt(ve - rs)}  |  Song ${fmt(s.srcStartMs)} - ${fmt(s.srcEndMs)}",
      0f, labelH - dp(4f), timesPaint
    )
    canvas.drawRoundRect(RectF(0f, top, width.toFloat(), bottom), corner, corner, trackPaint)

    val xl = xFor(vs, rs, re)
    val xr = max(xFor(ve, rs, re), xl + dp(4f))
    val hw = min(handleW, (xr - xl) / 2f)

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
      val label = TextUtils.ellipsize(s.title, labelPaint, avail, TextUtils.TruncateAt.END).toString()
      val baseline = midY - (labelPaint.ascent() + labelPaint.descent()) / 2f
      canvas.drawText(label, xl + hw + pad, baseline, labelPaint)
    }

    if (currentMs in rs..re) {
      val px = xFor(currentMs, rs, re)
      canvas.drawLine(px, top - dp(2f), px, bottom, playheadPaint)
    }
  }

  /** Crops/moves [s] so it lies inside [a]..[b]. Returns false if there is no room. */
  private fun fit(s: SongItem, a: Long, b: Long): Boolean {
    if (b - a < MIN_MS) return false
    val overlap = min(s.videoEndMs, b) - max(s.videoStartMs, a)
    if (overlap < MIN_MS) {
      val newLen = min(s.srcEndMs - s.srcStartMs, b - a)
      s.srcEndMs = s.srcStartMs + newLen
      s.videoStartMs = a
    } else {
      if (s.videoStartMs < a) {
        s.srcStartMs += a - s.videoStartMs
        s.videoStartMs = a
      }
      if (s.videoEndMs > b) s.srcEndMs -= s.videoEndMs - b
    }
    return true
  }

  override fun onTouchEvent(e: MotionEvent): Boolean {
    val s = item ?: return false
    val (rs, re) = rangeProvider?.invoke() ?: return false
    if (re <= rs || width == 0) return false

    when (e.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        val others = othersProvider?.invoke(s.id) ?: emptyList()
        lo = max(rs, others.filter { it.videoStartMs < s.videoStartMs }.maxOfOrNull { it.videoEndMs } ?: rs)
        hi = min(re, others.filter { it.videoStartMs > s.videoStartMs }.minOfOrNull { it.videoStartMs } ?: re)

        val b0 = s.videoStartMs; val b1 = s.srcStartMs; val b2 = s.srcEndMs
        if (!fit(s, lo, hi)) return false
        changed = b0 != s.videoStartMs || b1 != s.srcStartMs || b2 != s.srcEndMs

        iVideoStart = s.videoStartMs; iSrcStart = s.srcStartMs; iSrcEnd = s.srcEndMs
        val xl = xFor(s.videoStartMs, rs, re)
        val xr = xFor(s.videoEndMs, rs, re)
        val nearL = e.x >= xl - grabSlop && e.x <= xl + handleW
        val nearR = e.x >= xr - handleW && e.x <= xr + grabSlop
        mode = when {
          nearL && nearR -> if (abs(e.x - xl) <= abs(e.x - xr)) Mode.LEFT else Mode.RIGHT
          nearL -> Mode.LEFT
          nearR -> Mode.RIGHT
          e.x in xl..xr -> Mode.MOVE
          else -> { if (changed) onChanged?.invoke(); changed = false; return false }
        }
        downX = e.x
        invalidate()
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        val m = mode ?: return false
        val dt = ((e.x - downX) / width * (re - rs)).toLong()
        val len = iSrcEnd - iSrcStart
        when (m) {
          Mode.LEFT -> {
            val minV = max(lo, iVideoStart - iSrcStart)
            val maxV = iVideoStart + len - MIN_MS
            val nv = clampTo(iVideoStart + dt, minV, maxV)
            s.videoStartMs = nv
            s.srcStartMs = iSrcStart + (nv - iVideoStart)
          }
          Mode.RIGHT -> {
            val minE = iVideoStart + MIN_MS
            val maxE = min(hi, iVideoStart + (s.durationMs - iSrcStart))
            val ne = clampTo(iVideoStart + len + dt, minE, maxE)
            s.srcEndMs = iSrcStart + (ne - iVideoStart)
          }
          Mode.MOVE -> s.videoStartMs = clampTo(iVideoStart + dt, lo, hi - len)
        }
        changed = true
        invalidate()
        onChanging?.invoke()
        return true
      }
      MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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