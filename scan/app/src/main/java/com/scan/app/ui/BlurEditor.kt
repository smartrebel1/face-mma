package com.scan.app.ui

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize

/** One brush stroke, stored in source-bitmap pixels so what gets hidden never drifts. */
data class BlurStroke(val points: List<Offset>, val radius: Float)

/**
 * Preview with a "hide area" brush. Hidden regions are drawn as mosaic patches here, and the
 * exact same geometry is replayed on the full-resolution bitmap before the image leaves the app.
 */
@Composable
fun BlurEditor(
    original: Bitmap,
    mosaic: Bitmap,
    strokes: SnapshotStateList<BlurStroke>,
    brushRadius: Float,
    painting: Boolean,
    modifier: Modifier = Modifier
) {
    val image = remember(original) { original.asImageBitmap() }
    val mosaicImage = remember(mosaic) { mosaic.asImageBitmap() }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var current by remember { mutableStateOf<MutableList<Offset>?>(null) }

    val ratio = original.width.toFloat() / original.height.toFloat().coerceAtLeast(1f)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(16.dp))
            .background(ScanColors.Surface2)
            .onSizeChanged { boxSize = it }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (boxSize.width > 0 && painting) {
                        Modifier.pointerInput(boxSize, brushRadius) {
                            detectDragGestures(
                                onDragStart = { start ->
                                    val scale = original.width.toFloat() / size.width
                                    val pts = mutableListOf(
                                        Offset(start.x * scale, start.y * scale)
                                    )
                                    current = pts
                                    strokes.add(BlurStroke(pts.toList(), brushRadius))
                                },
                                onDrag = { change, _ ->
                                    val pts = current
                                    if (pts != null) {
                                        val scale = original.width.toFloat() / size.width
                                        val p = Offset(
                                            change.position.x * scale,
                                            change.position.y * scale
                                        )
                                        val last = pts.lastOrNull()
                                        if (last == null || (p - last).getDistance() > brushRadius * 0.2f) {
                                            pts.add(p)
                                            strokes[strokes.lastIndex] = BlurStroke(pts.toList(), brushRadius)
                                        }
                                    }
                                },
                                onDragEnd = { current = null },
                                onDragCancel = { current = null }
                            )
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            val scale = size.width / original.width.toFloat()
            drawImage(
                image = image,
                dstSize = IntSize(size.width.toInt(), (original.height * scale).toInt())
            )
            if (strokes.isNotEmpty()) {
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    val maskPaint = Paint().apply {
                        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
                        style = Paint.Style.FILL
                        color = android.graphics.Color.BLACK
                        isAntiAlias = true
                    }
                    val strokePaint = Paint(maskPaint).apply {
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                        strokeJoin = Paint.Join.ROUND
                    }
                    native.saveLayer(0f, 0f, size.width, size.height, null)
                    native.drawBitmap(mosaicImage.asAndroidBitmap(), 0f, 0f, null)
                    strokes.forEach { stroke ->
                        if (stroke.points.size == 1) {
                            native.drawCircle(
                                stroke.points[0].x * scale,
                                stroke.points[0].y * scale,
                                stroke.radius * scale,
                                maskPaint
                            )
                        } else if (stroke.points.size > 1) {
                            val path = android.graphics.Path()
                            path.moveTo(stroke.points[0].x * scale, stroke.points[0].y * scale)
                            for (i in 1 until stroke.points.size) {
                                path.lineTo(stroke.points[i].x * scale, stroke.points[i].y * scale)
                            }
                            strokePaint.strokeWidth = stroke.radius * 2 * scale
                            native.drawPath(path, strokePaint)
                        }
                    }
                    native.restore()
                }
            }
        }
    }
}

/** Bakes the mosaic patches into a new full-size bitmap -- this is what gets saved and sent. */
fun renderWithBlur(original: Bitmap, mosaic: Bitmap, strokes: List<BlurStroke>): Bitmap {
    val out = try {
        original.copy(Bitmap.Config.ARGB_8888, true)
    } catch (e: Exception) {
        original
    }
    if (strokes.isEmpty()) return out
    val canvas = android.graphics.Canvas(out)
    val maskPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        style = Paint.Style.FILL
        color = android.graphics.Color.BLACK
        isAntiAlias = true
    }
    val strokePaint = Paint(maskPaint).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    canvas.saveLayer(0f, 0f, out.width.toFloat(), out.height.toFloat(), null)
    canvas.drawBitmap(mosaic, 0f, 0f, null)
    strokes.forEach { stroke ->
        if (stroke.points.size == 1) {
            canvas.drawCircle(stroke.points[0].x, stroke.points[0].y, stroke.radius, maskPaint)
        } else if (stroke.points.size > 1) {
            val path = android.graphics.Path()
            path.moveTo(stroke.points[0].x, stroke.points[0].y)
            for (i in 1 until stroke.points.size) {
                path.lineTo(stroke.points[i].x, stroke.points[i].y)
            }
            strokePaint.strokeWidth = stroke.radius * 2
            canvas.drawPath(path, strokePaint)
        }
    }
    canvas.restore()
    return out
}
