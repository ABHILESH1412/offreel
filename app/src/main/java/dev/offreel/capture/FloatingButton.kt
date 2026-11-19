package dev.offreel.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The small round offreel button drawn over Instagram. Tap: save. Long-press: save straight into
 * the last group used. Drag: move it; on release it snaps to the nearest screen edge.
 */
@SuppressLint("ViewConstructor")
class FloatingButton(
    context: Context,
    private val wm: WindowManager,
    private val onTap: () -> Unit,
    private val onLongPress: () -> Unit,
    private val onMoved: (yFraction: Float, onLeft: Boolean) -> Unit,
) : View(context) {

    private val density = resources.displayMetrics.density
    private val sizePx = (52 * density).toInt()
    private val marginPx = (6 * density).toInt()
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6181A20.toInt() }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.2f * density; color = 0xFF2A2D36.toInt()
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.6f * density; strokeCap = Paint.Cap.ROUND; color = 0xFFF4EFE6.toInt()
    }
    private val play = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF5A36.toInt() }
    private val triangle = Path()
    private val arc = RectF()

    val params = WindowManager.LayoutParams(
        sizePx, sizePx,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START }

    init {
        contentDescription = "Save to offreel"
        isClickable = true
    }

    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    /** Puts the button at [yFraction] of the screen height on the left or right edge. */
    fun place(yFraction: Float, onLeft: Boolean) {
        params.x = if (onLeft) marginPx else screenW - sizePx - marginPx
        params.y = (screenH * yFraction).toInt().coerceIn(0, screenH - sizePx)
        if (isAttachedToWindow) wm.updateViewLayout(this, params)
    }

    var dragging = false
        private set

    override fun onDraw(canvas: Canvas) {
        val c = width / 2f
        canvas.drawCircle(c, c, c - rim.strokeWidth, fill)
        canvas.drawCircle(c, c, c - rim.strokeWidth, rim)
        // The offreel mark: an open cream ring with an ember play triangle inside.
        val r = width * 0.25f
        arc.set(c - r, c - r, c + r, c + r)
        canvas.drawArc(arc, -100f, 300f, false, ring)
        val t = r * 0.62f
        triangle.reset()
        triangle.moveTo(c - t * 0.55f, c - t)
        triangle.lineTo(c + t * 0.95f, c)
        triangle.lineTo(c - t * 0.55f, c + t)
        triangle.close()
        canvas.drawPath(triangle, play)
    }

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var longPressed = false
    private val longPressRunnable = Runnable {
        if (!dragging) {
            longPressed = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            onLongPress()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = e.rawX; downRawY = e.rawY
                startX = params.x; startY = params.y
                dragging = false; longPressed = false
                alpha = 0.85f
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                if (!dragging && hypot(dx, dy) > slop) {
                    dragging = true
                    removeCallbacks(longPressRunnable)
                }
                if (dragging) {
                    params.x = (startX + dx).toInt().coerceIn(0, screenW - sizePx)
                    params.y = (startY + dy).toInt().coerceIn(0, screenH - sizePx)
                    wm.updateViewLayout(this, params)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                alpha = 1f
                if (dragging) {
                    val onLeft = params.x + sizePx / 2 < screenW / 2
                    val y = params.y.toFloat() / screenH
                    place(y, onLeft)
                    onMoved(y, onLeft)
                    dragging = false
                } else if (!longPressed && e.actionMasked == MotionEvent.ACTION_UP &&
                    abs(e.rawX - downRawX) < slop && abs(e.rawY - downRawY) < slop
                ) {
                    performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                    performClick()
                    onTap()
                }
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
