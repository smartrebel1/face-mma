package com.scan.app.capture

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.View

/** The floating lens. Draws only -- dragging, tapping and long-press live in [LensController]. */
class LensBubbleView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density

    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#22D3EE")
        alpha = 60
        maskFilter = android.graphics.BlurMaskFilter(10f * density, android.graphics.BlurMaskFilter.Blur.NORMAL)
    }

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = RadialGradient(
            0.45f, 0.35f, 1.15f,
            intArrayOf(Color.parseColor("#2A3B4D"), Color.parseColor("#0C131B")),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * density
        color = Color.parseColor("#22D3EE")
    }

    private val bracketPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
        alpha = 230
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7FE9FB")
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = (60 * density).toInt()
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f

        canvas.drawCircle(cx, cy, r - 3 * density, shadowPaint)
        canvas.drawCircle(cx, cy, r - 3 * density, bgPaint)
        canvas.drawCircle(cx, cy, r - 3 * density, ringPaint)

        // corner brackets, the "scan" mark
        val b = r * 0.52f
        val len = r * 0.30f
        val l = cx - b
        val t = cy - b
        val rr = cx + b
        val bb = cy + b
        canvas.drawLine(l, t, l + len, t, bracketPaint)
        canvas.drawLine(l, t, l, t + len, bracketPaint)
        canvas.drawLine(rr - len, t, rr, t, bracketPaint)
        canvas.drawLine(rr, t, rr, t + len, bracketPaint)
        canvas.drawLine(l, bb - len, l, bb, bracketPaint)
        canvas.drawLine(l, bb, l + len, bb, bracketPaint)
        canvas.drawLine(rr, bb - len, rr, bb, bracketPaint)
        canvas.drawLine(rr - len, bb, rr, bb, bracketPaint)

        canvas.drawCircle(cx, cy, r * 0.16f, dotPaint)
    }
}
