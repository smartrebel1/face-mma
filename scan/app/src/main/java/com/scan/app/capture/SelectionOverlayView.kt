package com.scan.app.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen dim with a draggable / resizable hole. Corner handles resize, dragging inside
 * moves, dragging on empty space draws a new rectangle. The rectangle is reported in screen
 * pixels because the window is added at 0,0 with FLAG_LAYOUT_IN_SCREEN.
 */
@SuppressLint("ViewConstructor")
class SelectionOverlayView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private val minSize = 64 * density
    private val handleRadius = 11 * density
    private val touchSlop = 18 * density

    private val rect = RectF()
    private var initialised = false

    private val dimPaint = Paint().apply { color = Color.parseColor("#B3000000") }
    private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * density
        color = Color.parseColor("#22D3EE")
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22D3EE")
    }
    private val handleInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A0E14")
    }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1 * density
        color = Color.WHITE
        alpha = 60
    }

    private enum class Mode { NONE, MOVE, TL, TR, BL, BR, CREATE }
    private var mode = Mode.NONE
    private val corners = FloatArray(8)
    private var lastX = 0f
    private var lastY = 0f
    private var createStartX = 0f
    private var createStartY = 0f

    var onRectChanged: (() -> Unit)? = null

    init {
        isClickable = true
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!initialised && w > 0 && h > 0) {
            rect.set(w * 0.10f, h * 0.28f, w * 0.90f, h * 0.70f)
            initialised = true
            onRectChanged?.invoke()
        }
    }

    fun selection(): Rect {
        val l = rect.left.toInt().coerceIn(0, max(0, width - 1))
        val t = rect.top.toInt().coerceIn(0, max(0, height - 1))
        val r = rect.right.toInt().coerceIn(l + 1, width)
        val b = rect.bottom.toInt().coerceIn(t + 1, height)
        return Rect(l, t, r, b)
    }

    fun hasSelection(): Boolean = !rect.isEmpty && rect.width() > minSize / 2 && rect.height() > minSize / 2

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val sc = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        canvas.drawRect(rect, clearPaint)
        canvas.restoreToCount(sc)

        canvas.drawRect(rect, borderPaint)

        val thirdW = rect.width() / 3f
        val thirdH = rect.height() / 3f
        canvas.drawLine(rect.left + thirdW, rect.top, rect.left + thirdW, rect.bottom, guidePaint)
        canvas.drawLine(rect.left + 2 * thirdW, rect.top, rect.left + 2 * thirdW, rect.bottom, guidePaint)
        canvas.drawLine(rect.left, rect.top + thirdH, rect.right, rect.top + thirdH, guidePaint)
        canvas.drawLine(rect.left, rect.top + 2 * thirdH, rect.right, rect.top + 2 * thirdH, guidePaint)

        corners[0] = rect.left; corners[1] = rect.top
        corners[2] = rect.right; corners[3] = rect.top
        corners[4] = rect.left; corners[5] = rect.bottom
        corners[6] = rect.right; corners[7] = rect.bottom
        var i = 0
        while (i < 8) {
            canvas.drawCircle(corners[i], corners[i + 1], handleRadius, handlePaint)
            canvas.drawCircle(corners[i], corners[i + 1], handleRadius * 0.42f, handleInner)
            i += 2
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = x
                lastY = y
                mode = when {
                    near(x, y, rect.left, rect.top) -> Mode.TL
                    near(x, y, rect.right, rect.top) -> Mode.TR
                    near(x, y, rect.left, rect.bottom) -> Mode.BL
                    near(x, y, rect.right, rect.bottom) -> Mode.BR
                    rect.contains(x, y) -> Mode.MOVE
                    else -> {
                        createStartX = x
                        createStartY = y
                        rect.set(x, y, x + 1, y + 1)
                        Mode.CREATE
                    }
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    Mode.MOVE -> {
                        val dx = x - lastX
                        val dy = y - lastY
                        var nL = rect.left + dx
                        var nT = rect.top + dy
                        var nR = rect.right + dx
                        var nB = rect.bottom + dy
                        if (nL < 0) { nR -= nL; nL = 0f }
                        if (nT < 0) { nB -= nT; nT = 0f }
                        if (nR > width) { nL -= (nR - width); nR = width.toFloat() }
                        if (nB > height) { nT -= (nB - height); nB = height.toFloat() }
                        rect.set(nL, nT, nR, nB)
                    }

                    Mode.TL -> rect.set(
                        min(x, rect.right - minSize), min(y, rect.bottom - minSize),
                        rect.right, rect.bottom
                    )

                    Mode.TR -> rect.set(
                        rect.left, min(y, rect.bottom - minSize),
                        max(x, rect.left + minSize), rect.bottom
                    )

                    Mode.BL -> rect.set(
                        min(x, rect.right - minSize), rect.top,
                        rect.right, max(y, rect.top + minSize)
                    )

                    Mode.BR -> rect.set(
                        rect.left, rect.top,
                        max(x, rect.left + minSize), max(y, rect.top + minSize)
                    )

                    Mode.CREATE -> rect.set(
                        min(createStartX, x), min(createStartY, y),
                        max(createStartX, x), max(createStartY, y)
                    )

                    Mode.NONE -> Unit
                }
                lastX = x
                lastY = y
                onRectChanged?.invoke()
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                rect.left = rect.left.coerceIn(0f, width.toFloat())
                rect.top = rect.top.coerceIn(0f, height.toFloat())
                rect.right = rect.right.coerceIn(0f, width.toFloat())
                rect.bottom = rect.bottom.coerceIn(0f, height.toFloat())
                if (rect.width() < minSize || rect.height() < minSize) {
                    if (rect.width() < minSize) rect.right = (rect.left + minSize).coerceAtMost(width.toFloat())
                    if (rect.height() < minSize) rect.bottom = (rect.top + minSize).coerceAtMost(height.toFloat())
                }
                mode = Mode.NONE
                onRectChanged?.invoke()
                invalidate()
                return true
            }
        }
        return true
    }

    private fun near(x: Float, y: Float, px: Float, py: Float): Boolean =
        abs(x - px) <= touchSlop + handleRadius && abs(y - py) <= touchSlop + handleRadius
}
