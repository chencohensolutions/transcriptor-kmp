package com.chencohensolution.transcriptor.speech

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.chencohensolution.transcriptor.models.SpeechModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Where a model's download stands. */
sealed interface ModelStatus {
    data object Missing : ModelStatus
    data class Downloading(val progress: Float) : ModelStatus
    data object WaitingForWifi : ModelStatus
    data object Checking : ModelStatus
    data object Ready : ModelStatus
    data class Failed(val reason: String) : ModelStatus
}

/**
 * The models on the phone, in the app's external files (`Android/data/<app>/files/models/`), each `<id>.bin`.
 * Downloaded by the system's DownloadManager (it goes on in the background, resumes, and shows its own
 * notification), over Wi-Fi only, to `<id>.part`; then checked against the catalog's sha256, quantized when the
 * catalog says so, and moved into place, with `<id>.ok` marking it ready.
 */
class ModelFiles(private val context: Context) {
    private val dir = File(context.getExternalFilesDir(null), "models").apply { mkdirs() }
    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("model-downloads", Context.MODE_PRIVATE)

    fun file(model: SpeechModel) = File(dir, "${model.id}.bin")
    private fun part(model: SpeechModel) = File(dir, "${model.id}.part")

    private fun marker(model: SpeechModel) = File(dir, "${model.id}.ok")

    /** Downloaded, checked, and (when the catalog says so) quantized. */
    fun isReady(model: SpeechModel) = marker(model).exists() && file(model).exists()

    fun download(model: SpeechModel) {
        if (isReady(model) || prefs.contains(model.id)) return
        part(model).delete()
        val id = downloads.enqueue(
            DownloadManager.Request(Uri.parse(model.fileUrl))
                .setTitle(model.name)
                .setDestinationUri(Uri.fromFile(part(model)))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setAllowedOverMetered(false),
        )
        prefs.edit().putLong(model.id, id).apply()
    }

    fun cancel(model: SpeechModel) {
        prefs.getLong(model.id, -1).takeIf { it >= 0 }?.let { downloads.remove(it) }
        prefs.edit().remove(model.id).apply()
        part(model).delete()
    }

    fun delete(model: SpeechModel) {
        cancel(model)
        marker(model).delete()
        file(model).delete()
    }

    /** The download's state; a finished download is checked and moved into place here (seconds, on IO). */
    suspend fun status(model: SpeechModel): ModelStatus {
        if (isReady(model)) return ModelStatus.Ready
        val id = prefs.getLong(model.id, -1)
        if (id < 0) return ModelStatus.Missing
        downloads.query(DownloadManager.Query().setFilterById(id)).use { c ->
            if (!c.moveToFirst()) { prefs.edit().remove(model.id).apply(); return ModelStatus.Missing }
            val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
            val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            return when (status) {
                DownloadManager.STATUS_SUCCESSFUL -> verify(model)
                DownloadManager.STATUS_FAILED -> { cancel(model); ModelStatus.Failed("download error $reason") }
                else -> if (status == DownloadManager.STATUS_PAUSED && reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI) {
                    ModelStatus.WaitingForWifi
                } else {
                    ModelStatus.Downloading(done.toFloat() / model.sizeBytes)
                }
            }
        }
    }

    private val verifying = mutableSetOf<String>()

    private suspend fun verify(model: SpeechModel): ModelStatus {
        if (!synchronized(verifying) { verifying.add(model.id) }) return ModelStatus.Checking
        try {
            return check(model)
        } finally {
            synchronized(verifying) { verifying.remove(model.id) }
        }
    }

    private suspend fun check(model: SpeechModel): ModelStatus = withContext(Dispatchers.IO) {
        val part = part(model)
        val digest = MessageDigest.getInstance("SHA-256")
        part.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        prefs.edit().remove(model.id).apply()
        if (hex != model.sha256) {
            part.delete()
            return@withContext ModelStatus.Failed("the file's checksum doesn't match")
        }
        val type = model.quantizeTo
        if (type == null) {
            part.renameTo(file(model))
        } else {
            val temp = File(dir, "${model.id}.quantizing")
            val ok = WhisperEngine.quantize(part.path, temp.path, type)
            part.delete()
            if (!ok) {
                temp.delete()
                return@withContext ModelStatus.Failed("couldn't quantize to $type")
            }
            temp.renameTo(file(model))
        }
        marker(model).createNewFile()
        ModelStatus.Ready
    }
}
