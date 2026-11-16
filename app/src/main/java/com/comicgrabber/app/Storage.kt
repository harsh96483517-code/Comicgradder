package com.comicgrabber.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/** A writable destination inside the public Downloads/ComicGrabber folder. */
class Sink(val stream: OutputStream, val finish: () -> Unit, val abort: () -> Unit)

object Storage {
    private const val FOLDER = "ComicGrabber"

    fun open(ctx: Context, name: String, mime: String): Sink =
        if (Build.VERSION.SDK_INT >= 29) openMediaStore(ctx, name, mime) else openLegacy(ctx, name)

    @RequiresApi(29)
    private fun openMediaStore(ctx: Context, name: String, mime: String): Sink {
        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create file in Downloads")
        val os = resolver.openOutputStream(uri) ?: error("Could not open output stream")
        return Sink(
            stream = os,
            finish = {
                os.close()
                resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            },
            abort = {
                runCatching { os.close() }
                resolver.delete(uri, null, null)
            }
        )
    }

    private fun openLegacy(ctx: Context, name: String): Sink {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        dir.mkdirs()
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var file = File(dir, name)
        var i = 1
        while (file.exists()) file = File(dir, "$base (${i++}).$ext")
        val os = FileOutputStream(file)
        return Sink(
            stream = os,
            finish = {
                os.close()
                MediaScannerConnection.scanFile(ctx, arrayOf(file.absolutePath), null, null)
            },
            abort = {
                runCatching { os.close() }
                file.delete()
            }
        )
    }
}
