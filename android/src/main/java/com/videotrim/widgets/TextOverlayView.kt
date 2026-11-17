package com.videotrim.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * One text item. Position is the item's center as a fraction (0..1) of the
 * displayed video frame (after rotate/flip, before crop). Text height is
 * BASE_HEIGHT_FRACTION * frameHeight * scale, so the export can reproduce it.
 */
class TextItem(
  val id: Long,
  var text: String,
  var startMs: Long,
  var endMs: Long,
  var cx: Float = 0.5f,
  var cy: Float = 0.5f,
  var scale: Float = 1f,
  var rotationDeg: Float = 0f,
  var color: Int = Color.WHITE
)

class TextOverlayView(context: Context) : View(context) {

  companion object {
    const val BASE_HEIGHT_FRACTION = 0.06f
  }

  val items = mutableListOf<TextItem>()
  var selectedId: Long = -1L
    private set

  var rectProvider: (() -> RectF)? = null
  var onItemTap: ((TextItem) -> Unit)? = null
  var onChanged: (() -> Unit)? = null
  var onSelectionChanged: ((TextItem?) -> Unit)? = null

  private var currentMs = 0L
  private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
  private val hitPad = dp(12f)

  private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textAlign = Paint.Align.CENTER
    setShadowLayer(dp(2f), 0f, dp(1f), Color.argb(160, 0, 0, 0))
  }
  private val selectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    color = Color.WHITE
    strokeWidth = dp(1f)
    pathEffect = DashPathEffect(floatArrayOf(dp(6f), dp(4f)), 0f)
  }

  fun setCurrentMs(ms: Long) {
    currentMs = ms
    invalidate()
  }

  fun addItem(item: TextItem) {
    items.add(item)
    select(item.id)
    onChanged?.invoke()
  }

  fun removeItem(id: Long) {
    items.removeAll { it.id == id }
    if (selectedId == id) select(-1L)
    onChanged?.invoke()
    invalidate()
  }

  fun selectedItem(): TextItem? = items.firstOrNull { it.id == selectedId }

  private fun select(id: Long) {
    if (selectedId == id) { invalidate(); return }
    selectedId = id
    onSelectionChanged?.invoke(selectedItem())
    invalidate()
  }

  private fun isVisibleNow(i: TextItem) = currentMs >= i.startMs && currentMs <= i.endMs

  private fun textSizePx(rect: RectF, i: TextItem) = BASE_HEIGHT_FRACTION * rect.height() * i.scale

  private fun halfExtents(rect: RectF, i: TextItem): Pair<Float, Float> {
    textPaint.textSize = textSizePx(rect, i)
    return (textPaint.measureText(i.text) / 2f + hitPad) to (textPaint.textSize / 2f + hitPad)
  }

  override fun onDraw(canvas: Canvas) {
    val rect = rectProvider?.invoke() ?: return
    if (rect.isEmpty) return
    for (i in items) {
      val selected = i.id == selectedId
      if (!isVisibleNow(i) && !selected) continue
      textPaint.textSize = textSizePx(rect, i)
      textPaint.color = i.color
      textPaint.alpha = if (isVisibleNow(i)) 255 else 90
      canvas.save()
      canvas.translate(rect.left + i.cx * rect.width(), rect.top + i.cy * rect.height())
      canvas.rotate(i.rotationDeg)
      val baseline = -(textPaint.ascent() + textPaint.descent()) / 2f
      canvas.drawText(i.text, 0f, baseline, textPaint)
      if (selected) {
        val (hw, hh) = halfExtents(rect, i)
        canvas.drawRect(-hw, -hh, hw, hh, selectPaint)
      }
      canvas.restore()
    }
  }

  private fun hitTest(x: Float, y: Float, rect: RectF): TextItem? {
    for (i in items.asReversed()) {
      if (!isVisibleNow(i) && i.id != selectedId) continue
      val dx = x - (rect.left + i.cx * rect.width())
      val dy = y - (rect.top + i.cy * rect.height())
      val a = Math.toRadians(-i.rotationDeg.toDouble())
      val lx = (dx * cos(a) - dy * sin(a)).toFloat()
      val ly = (dx * sin(a) + dy * cos(a)).toFloat()
      val (hw, hh) = halfExtents(rect, i)
      if (lx >= -hw && lx <= hw && ly >= -hh && ly <= hh) return i
    }
    return null
  }

  private var lastX = 0f
  private var lastY = 0f
  private var downX = 0f
  private var downY = 0f
  private var moved = false
  private var wasSelectedOnDown = false
  private var pinchStartDist = 0f
  private var pinchStartAngle = 0f
  private var pinchStartScale = 1f
  private var pinchStartRot = 0f
  private var changedByUser = false

  override fun onTouchEvent(e: MotionEvent): Boolean {
    val rect = rectProvider?.invoke() ?: return false
    if (rect.isEmpty) return false

    when (e.actionMasked) {
      MotionEvent.ACTION_DOWN -> {
        val hit = hitTest(e.x, e.y, rect) ?: return false
        wasSelectedOnDown = hit.id == selectedId
        select(hit.id)
        // Bring to front.
        items.remove(hit); items.add(hit)
        lastX = e.x; lastY = e.y; downX = e.x; downY = e.y
        moved = false
        changedByUser = false
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
      }
      MotionEvent.ACTION_POINTER_DOWN -> {
        val item = selectedItem() ?: return true
        if (e.pointerCount == 2) {
          pinchStartDist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)).coerceAtLeast(1f)
          pinchStartAngle = Math.toDegrees(
            atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())
          ).toFloat()
          pinchStartScale = item.scale
          pinchStartRot = item.rotationDeg
          moved = true
        }
        return true
      }
      MotionEvent.ACTION_MOVE -> {
        val item = selectedItem() ?: return false
        if (e.pointerCount >= 2) {
          val dist = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
          val angle = Math.toDegrees(
            atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())
          ).toFloat()
          item.scale = (pinchStartScale * dist / pinchStartDist).coerceIn(0.3f, 8f)
          item.rotationDeg = pinchStartRot + (angle - pinchStartAngle)
          changedByUser = true
          invalidate()
          return true
        }
        if (!moved && hypot(e.x - downX, e.y - downY) < touchSlop) return true
        moved = true
        item.cx = (item.cx + (e.x - lastX) / rect.width()).coerceIn(0f, 1f)
        item.cy = (item.cy + (e.y - lastY) / rect.height()).coerceIn(0f, 1f)
        lastX = e.x; lastY = e.y
        changedByUser = true
        invalidate()
        return true
      }
      MotionEvent.ACTION_POINTER_UP -> {
        // Re-anchor the drag to the finger that remains, so the item doesn't jump.
        val remaining = if (e.actionIndex == 0) 1 else 0
        lastX = e.getX(remaining); lastY = e.getY(remaining)
        return true
      }
      MotionEvent.ACTION_UP -> {
        val item = selectedItem()
        if (!moved && wasSelectedOnDown && item != null) onItemTap?.invoke(item)
        if (changedByUser) onChanged?.invoke()
        return true
      }
      MotionEvent.ACTION_CANCEL -> return true
    }
    return false
  }

  private fun dp(v: Float) =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}