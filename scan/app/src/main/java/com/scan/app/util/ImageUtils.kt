package com.scan.app.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

object ImageUtils {

    /** Decodes a file, downsampled so the longest side is at most [maxSide]. */
    fun loadSampled(path: String, maxSide: Int = 2048): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > maxSide * 2) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return try {
            BitmapFactory.decodeFile(path, opts)
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        }
    }

    fun scaleMax(bmp: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= maxSide) return bmp
        val ratio = maxSide.toFloat() / longest
        val w = (bmp.width * ratio).toInt().coerceAtLeast(1)
        val h = (bmp.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, w, h, true)
    }

    fun toJpegBase64(bmp: Bitmap, maxSide: Int = 1600, quality: Int = 85): String {
        val scaled = scaleMax(bmp, maxSide)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (scaled !== bmp) scaled.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    fun savePng(bmp: Bitmap, file: File): Boolean = try {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        true
    } catch (e: Exception) {
        false
    }

    fun saveJpeg(bmp: Bitmap, file: File, quality: Int = 92): Boolean = try {
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { out ->
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
        }
        true
    } catch (e: Exception) {
        false
    }

    /** Mosaic "blur" used by the hide-area brush: fast, and impossible to reverse. */
    fun mosaic(src: Bitmap, block: Int = 18): Bitmap {
        val smallW = (src.width / block).coerceAtLeast(1)
        val smallH = (src.height / block).coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(src, smallW, smallH, false)
        val up = Bitmap.createScaledBitmap(small, src.width, src.height, true)
        if (small !== up) small.recycle()
        return up
    }
}
