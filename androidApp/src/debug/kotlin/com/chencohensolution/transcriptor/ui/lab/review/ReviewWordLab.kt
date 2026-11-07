package com.chencohensolution.transcriptor.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore

/**
 * The lab's way out for the lecture's Word file (the app's own, [com.chencohensolution.transcriptor.notebook.transcriptDocx]):
 * saved in Downloads/Transcriptor, since the lab has no lecture folders, and shared from there.
 */
internal object LabWord {
    const val MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    /** Saves it in Downloads/Transcriptor (the lab can't reach the lectures folder) and returns where. */
    fun save(context: Context, fileName: String, bytes: ByteArray): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/Transcriptor")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
        context.contentResolver.openOutputStream(uri)!!.use { it.write(bytes) }
        return uri
    }

    /** Android's share sheet with the file (Word, Drive, mail, WhatsApp…). */
    fun share(context: Context, uri: Uri, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

}
