package com.scan.app.capture

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * One consent token, one virtual display, one screenshot -- which is exactly what Android 14
 * allows. The projection is stopped as soon as the frame is in memory, so nothing keeps
 * mirroring the screen behind the user's back.
 */
class ScreenCapture(private val ctx: Context) {

    class CaptureError(val kind: Kind, message: String = "") : Exception(message) {
        enum class Kind { PERMISSION, TIMEOUT, UNKNOWN }
    }

    suspend fun capture(resultCode: Int, data: Intent, crop: Rect?): Bitmap {
        val manager = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection: MediaProjection = try {
            manager.getMediaProjection(resultCode, data)
        } catch (e: SecurityException) {
            throw CaptureError(CaptureError.Kind.PERMISSION, e.message ?: "expired consent")
        } ?: throw CaptureError(CaptureError.Kind.PERMISSION, "no projection")

        val handler = Handler(Looper.getMainLooper())
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {}
        }
        projection.registerCallback(callback, handler)

        val (w, h) = screenSize()
        val density = ctx.resources.displayMetrics.densityDpi
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        var display: VirtualDisplay? = null

        try {
            display = projection.createVirtualDisplay(
                "scan-capture",
                w,
                h,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                handler
            )
            val image = withTimeoutOrNull(8000) { awaitImage(reader, handler) }
                ?: throw CaptureError(CaptureError.Kind.TIMEOUT, "no frame in 8s")
            val full = try {
                imageToBitmap(image)
            } finally {
                image.close()
            }
            return cropAndOpaque(full, crop)
        } finally {
            try {
                display?.release()
            } catch (e: Exception) {
                // ignore
            }
            try {
                reader.close()
            } catch (e: Exception) {
                // ignore
            }
            try {
                projection.unregisterCallback(callback)
            } catch (e: Exception) {
                // ignore
            }
            try {
                projection.stop()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private suspend fun awaitImage(reader: ImageReader, handler: Handler): Image? =
        suspendCancellableCoroutine { cont ->
            reader.setOnImageAvailableListener({ r ->
                val img = try {
                    r.acquireLatestImage()
                } catch (e: Exception) {
                    null
                }
                if (img != null) {
                    r.setOnImageAvailableListener(null, null)
                    if (cont.isActive) cont.resume(img)
                }
            }, handler)
            cont.invokeOnCancellation {
                try {
                    reader.setOnImageAvailableListener(null, null)
                } catch (e: Exception) {
                    // ignore
                }
            }
        }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width
        val paddedWidth = image.width + rowPadding / pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        buffer.rewind()
        padded.copyPixelsFromBuffer(buffer)
        if (paddedWidth == image.width) return padded
        val cut = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
        padded.recycle()
        return cut
    }

    private fun cropAndOpaque(full: Bitmap, crop: Rect?): Bitmap {
        var bmp = full
        if (crop != null && !crop.isEmpty) {
            val l = crop.left.coerceIn(0, full.width - 1)
            val t = crop.top.coerceIn(0, full.height - 1)
            val r = crop.right.coerceIn(l + 1, full.width)
            val b = crop.bottom.coerceIn(t + 1, full.height)
            if (l != 0 || t != 0 || r != full.width || b != full.height) {
                bmp = Bitmap.createBitmap(full, l, t, r - l, b - t)
                if (bmp !== full) full.recycle()
            }
        }
        // Some GPUs hand back alpha=0 in the RGBA_8888 buffer; force the pixels opaque or the
        // preview would look transparent.
        val out = forceOpaque(bmp)
        if (out !== bmp) bmp.recycle()
        return out
    }

    private fun forceOpaque(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        try {
            src.getPixels(pixels, 0, w, 0, 0, w, h)
        } catch (e: OutOfMemoryError) {
            return src
        }
        for (i in pixels.indices) {
            pixels[i] = pixels[i] or 0xFF000000.toInt()
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun screenSize(): Pair<Int, Int> {
        val manager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = manager.currentWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            manager.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels to dm.heightPixels
        }
    }
}
