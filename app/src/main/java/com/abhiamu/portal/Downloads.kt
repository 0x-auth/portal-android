package com.abhiamu.portal

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.io.OutputStream

/** Writes a file into the public Downloads folder (MediaStore on Android 10+, plain file below). */
object Downloads {

    fun save(ctx: Context, rawName: String, mime: String?, write: (OutputStream) -> Unit): String {
        val name = safeName(rawName, mime)
        if (Build.VERSION.SDK_INT >= 29) {
            val r = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Couldn't create the file")
            try {
                r.openOutputStream(uri)!!.use(write)
            } catch (e: Exception) {
                r.delete(uri, null, null); throw e
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            r.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
            val stem = name.substringBeforeLast('.', name)
            val ext = if ('.' in name) "." + name.substringAfterLast('.') else ""
            var f = File(dir, name)
            var n = 1
            while (f.exists()) f = File(dir, "$stem (${n++})$ext")
            f.outputStream().use(write)
        }
        return name
    }

    /** Strip path characters and make sure the name has an extension matching the MIME type. */
    fun safeName(raw: String?, mime: String?): String {
        var n = (raw ?: "").substringAfterLast('/').replace(Regex("""[\\/:*?"<>|\u0000-\u001f]"""), "_").trim()
        if (n.isEmpty() || n == "." || n == "..") n = "download"
        if (!n.contains('.')) {
            MimeTypeMap.getSingleton().getExtensionFromMimeType(mime?.substringBefore(';')?.trim())
                ?.let { n += ".$it" }
        }
        return n.take(120)
    }
}
