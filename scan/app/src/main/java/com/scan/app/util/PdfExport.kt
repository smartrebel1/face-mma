package com.scan.app.util

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import java.io.File
import java.io.FileOutputStream

/**
 * One-file PDF export for a history item: heading, screenshot (if still on disk) and the
 * analysis text, laid out with StaticLayout so Arabic is shaped and ordered correctly.
 */
object PdfExport {

    private const val PAGE_W = 595
    private const val PAGE_H = 842
    private const val MARGIN = 40f

    fun export(
        context: Context,
        heading: String,
        body: String,
        imagePath: String?,
        footer: String
    ): File? {
        val doc = PdfDocument()
        val textPaint = TextPaint().apply {
            textSize = 11f
            color = Color.BLACK
            isAntiAlias = true
        }
        val headingPaint = TextPaint().apply {
            textSize = 16f
            color = Color.BLACK
            isAntiAlias = true
            isFakeBoldText = true
        }
        var pageNo = 0
        var remaining = body.ifBlank { "—" }
        var first = true
        var guard = 0

        while (guard++ < 60) {
            pageNo++
            val page = doc.startPage(
                PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo).create()
            )
            val canvas = page.canvas
            var y = MARGIN + 10f

            if (first) {
                canvas.drawText(heading, MARGIN, y, headingPaint)
                y += 26f
                if (imagePath != null) {
                    val file = File(imagePath)
                    if (file.exists()) {
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(imagePath, bounds)
                        if (bounds.outWidth > 0) {
                            val maxW = PAGE_W - 2 * MARGIN
                            val maxH = 300f
                            val scale = minOf(maxW / bounds.outWidth, maxH / bounds.outHeight)
                            val w = bounds.outWidth * scale
                            val h = bounds.outHeight * scale
                            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                            val bmp = try {
                                BitmapFactory.decodeFile(imagePath, opts)
                            } catch (e: OutOfMemoryError) {
                                null
                            }
                            if (bmp != null) {
                                canvas.drawBitmap(
                                    bmp,
                                    null,
                                    android.graphics.RectF(MARGIN, y, MARGIN + w, y + h),
                                    null
                                )
                                bmp.recycle()
                                y += h + 18f
                            }
                        }
                    }
                }
                first = false
            }

            val available = PAGE_H - MARGIN - y
            val width = (PAGE_W - 2 * MARGIN).toInt()
            val layout = build(remaining, textPaint, width)
            if (layout.lineCount == 0) {
                doc.finishPage(page)
                break
            }
            val lineHeight = (layout.getLineBottom(0) - layout.getLineTop(0)).coerceAtLeast(1)
            val maxLines = (available / lineHeight).toInt().coerceAtLeast(1)

            if (layout.lineCount <= maxLines) {
                canvas.save()
                canvas.translate(MARGIN, y)
                layout.draw(canvas)
                canvas.restore()
                doc.finishPage(page)
                break
            } else {
                val end = layout.getLineEnd(maxLines - 1).coerceIn(1, remaining.length)
                val chunk = build(remaining.substring(0, end), textPaint, width)
                canvas.save()
                canvas.translate(MARGIN, y)
                chunk.draw(canvas)
                canvas.restore()
                canvas.drawText(
                    footer,
                    MARGIN,
                    PAGE_H - 24f,
                    TextPaint().apply {
                        textSize = 8f
                        color = Color.GRAY
                        isAntiAlias = true
                    }
                )
                doc.finishPage(page)
                remaining = remaining.substring(end)
                if (remaining.isBlank()) break
            }
        }

        return try {
            val file = File(context.cacheDir, "scan_report_${System.currentTimeMillis()}.pdf")
            FileOutputStream(file).use { doc.writeTo(it) }
            file
        } catch (e: Exception) {
            null
        } finally {
            try {
                doc.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun build(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
            .setLineSpacing(0f, 1.08f)
            .setIncludePad(false)
            .build()
}
