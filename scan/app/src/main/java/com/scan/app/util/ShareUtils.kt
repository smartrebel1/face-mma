package com.scan.app.util

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object ShareUtils {

    fun shareText(context: Context, text: String, subject: String = "SCAN") {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(i, "مشاركة"))
    }

    fun uriFor(context: Context, file: File): android.net.Uri =
        FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)

    fun shareFile(context: Context, file: File, mime: String, title: String) {
        val uri = try {
            uriFor(context, file)
        } catch (e: Exception) {
            return
        }
        val i = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(i, title))
    }

    fun openFile(context: Context, file: File, mime: String) {
        val uri = try {
            uriFor(context, file)
        } catch (e: Exception) {
            return
        }
        val i = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(i)
        } catch (e: Exception) {
            shareFile(context, file, mime, "مشاركة")
        }
    }
}
