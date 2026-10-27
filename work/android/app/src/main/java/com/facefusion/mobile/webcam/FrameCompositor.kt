package com.facefusion.mobile.webcam

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure placement geometry for rendering a source video frame of size ([srcW] x [srcH])
 * into an output canvas of size ([dstW] x [dstH]) under [mode].
 */
data class PlacementRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val canvasWidth: Int,
    val canvasHeight: Int,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * Composites raw video frames with Aspect Ratio (`Fit`, `Fill`, `Original`) and `Mirror`
 * transformations before handing them to the active live stream output sink.
 */
object FrameCompositor {

    /**
     * Computes where the source frame ([srcW]x[srcH]) should be drawn on an output surface
     * ([targetW]x[targetH]) for the given [mode].
     *
     * - [AspectRatioMode.FIT]: Uniform scale so the entire source frame fits inside
     *   [targetW]x[targetH] with black letterbox/pillarbox bars as needed.
     * - [AspectRatioMode.FILL]: Uniform scale so the source frame completely covers
     *   [targetW]x[targetH] (center crop).
     * - [AspectRatioMode.ORIGINAL]: Preserves the source frame's exact aspect ratio by
     *   sizing the output canvas to match the source aspect within the maximum edge of
     *   ([targetW], [targetH]), rounded to even dimensions for video encoders.
     */
    fun computePlacement(
        srcW: Int,
        srcH: Int,
        targetW: Int,
        targetH: Int,
        mode: AspectRatioMode,
    ): PlacementRect {
        val safeSrcW = srcW.coerceAtLeast(2)
        val safeSrcH = srcH.coerceAtLeast(2)
        val safeDstW = (targetW.coerceAtLeast(2) and 1.inv()).coerceAtLeast(2)
        val safeDstH = (targetH.coerceAtLeast(2) and 1.inv()).coerceAtLeast(2)

        return when (mode) {
            AspectRatioMode.FIT -> {
                val scale = min(
                    safeDstW.toFloat() / safeSrcW.toFloat(),
                    safeDstH.toFloat() / safeSrcH.toFloat(),
                )
                val w = safeSrcW * scale
                val h = safeSrcH * scale
                val l = (safeDstW - w) / 2f
                val t = (safeDstH - h) / 2f
                PlacementRect(l, t, l + w, t + h, safeDstW, safeDstH)
            }
            AspectRatioMode.FILL -> {
                val scale = max(
                    safeDstW.toFloat() / safeSrcW.toFloat(),
                    safeDstH.toFloat() / safeSrcH.toFloat(),
                )
                val w = safeSrcW * scale
                val h = safeSrcH * scale
                val l = (safeDstW - w) / 2f
                val t = (safeDstH - h) / 2f
                PlacementRect(l, t, l + w, t + h, safeDstW, safeDstH)
            }
            AspectRatioMode.ORIGINAL -> {
                val maxEdge = max(safeDstW, safeDstH)
                val srcMax = max(safeSrcW, safeSrcH)
                val scale = if (srcMax > maxEdge) maxEdge.toFloat() / srcMax.toFloat() else 1f
                val outW = ((safeSrcW * scale).roundToInt().coerceAtLeast(2) and 1.inv()).coerceAtLeast(2)
                val outH = ((safeSrcH * scale).roundToInt().coerceAtLeast(2) and 1.inv()).coerceAtLeast(2)
                PlacementRect(0f, 0f, outW.toFloat(), outH.toFloat(), outW, outH)
            }
        }
    }

    /**
     * Pure pixel-array horizontal mirror for deterministic unit testing and software fallback.
     */
    fun mirrorPixelsHorizontally(pixels: IntArray, width: Int, height: Int): IntArray {
        require(width > 0 && height > 0 && pixels.size >= width * height) {
            "Invalid dimensions ${width}x${height} for buffer of size ${pixels.size}"
        }
        val out = IntArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                out[rowOffset + x] = pixels[rowOffset + (width - 1 - x)]
            }
        }
        return out
    }

    /**
     * Renders [source] onto a composited output [Bitmap] applying [aspectRatio] and [mirror].
     */
    fun compositeBitmap(
        source: Bitmap,
        targetW: Int,
        targetH: Int,
        aspectRatio: AspectRatioMode,
        mirror: Boolean,
        reuseBitmap: Bitmap? = null,
    ): Bitmap {
        val placement = computePlacement(
            srcW = source.width,
            srcH = source.height,
            targetW = targetW,
            targetH = targetH,
            mode = aspectRatio,
        )
        val outW = placement.canvasWidth
        val outH = placement.canvasHeight
        val out = if (reuseBitmap != null &&
            !reuseBitmap.isRecycled &&
            reuseBitmap.width == outW &&
            reuseBitmap.height == outH &&
            reuseBitmap.isMutable
        ) {
            reuseBitmap
        } else {
            Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        }

        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)

        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.save()
        if (mirror) {
            canvas.scale(-1f, 1f, outW / 2f, outH / 2f)
        }
        val dstRect = RectF(placement.left, placement.top, placement.right, placement.bottom)
        canvas.drawBitmap(source, null, dstRect, paint)
        canvas.restore()
        return out
    }

    /**
     * Creates a clean standby frame when output is ON but playback is stopped or no video is loaded.
     */
    fun createStandbyBitmap(
        width: Int,
        height: Int,
        titleText: String = "STANDBY • LIVE OUTPUT READY",
        subtitleText: String = "Waiting for video playback",
    ): Bitmap {
        val w = (width.coerceAtLeast(320) and 1.inv()).coerceAtLeast(320)
        val h = (height.coerceAtLeast(240) and 1.inv()).coerceAtLeast(240)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.rgb(14, 16, 22))

        val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(30, 36, 48)
            style = Paint.Style.FILL
        }
        val boxW = w * 0.78f
        val boxH = 110f
        val left = (w - boxW) / 2f
        val top = (h - boxH) / 2f
        canvas.drawRoundRect(RectF(left, top, left + boxW, top + boxH), 18f, 18f, badgePaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 230, 118)
            textSize = 24f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(180, 190, 205)
            textSize = 18f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(titleText, w / 2f, top + 46f, titlePaint)
        canvas.drawText(subtitleText, w / 2f, top + 82f, subPaint)
        return bmp
    }
}
