package com.chencohensolution.transcriptor.speech

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import com.chencohensolution.transcriptor.models.ModelFile
import com.chencohensolution.transcriptor.models.SpeechModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
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
 * The models on the phone, in the app's external files: `Android/data/<app>/files/models/<id>/`, one file per
 * [ModelFile], and `.ok` once the model is ready. Each file is downloaded by the system's DownloadManager (it goes
 * on in the background, resumes, and shows its own notification), over Wi-Fi only, to `<name>.part`; then checked
 * against the catalog's sha256, quantized when the catalog says so (a Whisper model's one file), and renamed.
 */
class ModelFiles(context: Context) {
    private val root = File(context.getExternalFilesDir(null), "models").apply { mkdirs() }
    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val prefs = context.getSharedPreferences("model-downloads", Context.MODE_PRIVATE)

    init {
        moveFromFlatLayout()
    }

    /** A model's folder, named by its id (an added model's "hf:owner/repo:size" with `_` for `/` and `:`). */
    fun dir(model: SpeechModel) = File(root, model.id.replace('/', '_').replace(':', '_'))
    fun file(model: SpeechModel, file: ModelFile = model.files.single()) = File(dir(model), file.name)
    private fun part(model: SpeechModel, file: ModelFile) = File(dir(model), "${file.name}.part")
    private fun marker(model: SpeechModel) = File(dir(model), ".ok")
    private fun key(model: SpeechModel, file: ModelFile) = "${model.id}/${file.name}"

    /** Downloaded, checked, and (when the catalog says so) quantized. */
    fun isReady(model: SpeechModel) = marker(model).exists() && model.files.all { file(model, it).exists() }

    /** Starts the download; a reason when it couldn't be started. */
    fun download(model: SpeechModel): String? = runCatching { enqueue(model) }.exceptionOrNull()?.let { it.message ?: it.toString() }

    private fun enqueue(model: SpeechModel) {
        if (isReady(model)) return
        dir(model).mkdirs()
        for (file in model.files) {
            if (prefs.contains(key(model, file))) continue
            part(model, file).delete()
            val id = downloads.enqueue(
                DownloadManager.Request(Uri.parse(file.url))
                    .setTitle("${model.name} · ${file.name}")
                    .setDestinationUri(Uri.fromFile(part(model, file)))
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setAllowedOverMetered(false),
            )
            prefs.edit().putLong(key(model, file), id).apply()
        }
    }

    fun cancel(model: SpeechModel) {
        for (file in model.files) {
            prefs.getLong(key(model, file), -1).takeIf { it >= 0 }?.let { downloads.remove(it) }
            prefs.edit().remove(key(model, file)).apply()
            part(model, file).delete()
        }
    }

    fun delete(model: SpeechModel) {
        cancel(model)
        dir(model).listFiles()?.forEach { it.delete() }
        dir(model).delete()
    }

    /** The download's state; a finished download is checked and moved into place here (seconds, on IO). */
    suspend fun status(model: SpeechModel): ModelStatus {
        if (isReady(model)) return ModelStatus.Ready
        if (model.files.none { prefs.contains(key(model, it)) }) return ModelStatus.Missing
        var done = 0L
        var waiting = false
        var finished = 0
        for (file in model.files) {
            val id = prefs.getLong(key(model, file), -1)
            if (id < 0) { cancel(model); return ModelStatus.Missing }
            downloads.query(DownloadManager.Query().setFilterById(id)).use { c ->
                if (!c.moveToFirst()) { cancel(model); return ModelStatus.Missing }
                val status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                done += c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)).coerceAtLeast(0)
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> finished++
                    DownloadManager.STATUS_FAILED -> { cancel(model); return ModelStatus.Failed("download error $reason") }
                    DownloadManager.STATUS_PAUSED -> if (reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI) waiting = true
                }
            }
        }
        return when {
            finished == model.files.size -> verify(model)
            waiting -> ModelStatus.WaitingForWifi
            else -> ModelStatus.Downloading(done.toFloat() / model.sizeBytes)
        }
    }

    private val verifying = mutableSetOf<String>()

    private suspend fun verify(model: SpeechModel): ModelStatus {
        if (!synchronized(verifying) { verifying.add(model.id) }) return ModelStatus.Checking
        try {
            return check(model)
        } catch (e: IOException) {
            cancel(model)
            return ModelStatus.Failed(e.message ?: e.toString())
        } finally {
            synchronized(verifying) { verifying.remove(model.id) }
        }
    }

    private suspend fun check(model: SpeechModel): ModelStatus = withContext(Dispatchers.IO) {
        for (file in model.files) {
            // A small file added from Hugging Face has no sha256 there: its git blob id checks it.
            val ok = if (file.sha256.isEmpty() && file.gitSha1 != null) gitSha1(part(model, file)) == file.gitSha1 else sha256(part(model, file)) == file.sha256
            if (!ok) {
                cancel(model)
                return@withContext ModelStatus.Failed("${file.name}: the checksum doesn't match")
            }
        }
        val type = model.quantizeTo
        for (file in model.files) {
            val part = part(model, file)
            if (type == null) {
                part.renameTo(file(model, file))
            } else {
                val temp = File(dir(model), "${file.name}.quantizing")
                val ok = WhisperEngine.quantize(part.path, temp.path, type)
                part.delete()
                if (!ok) {
                    temp.delete()
                    cancel(model)
                    return@withContext ModelStatus.Failed("couldn't quantize to $type")
                }
                temp.renameTo(file(model, file))
            }
            prefs.edit().remove(key(model, file)).apply()
        }
        marker(model).createNewFile()
        ModelStatus.Ready
    }

    /** Git's id of a file's content: the sha1 of "blob <size>\u0000" and the bytes. */
    private fun gitSha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${file.length()}\u0000".toByteArray())
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Before Nov 2, 2026 a Whisper model was `models/<id>.bin` with `<id>.ok`. */
    private fun moveFromFlatLayout() {
        root.listFiles { f -> f.name.endsWith(".ok") && f.isFile }?.forEach { ok ->
            val id = ok.name.removeSuffix(".ok")
            val bin = File(root, "$id.bin")
            if (bin.exists()) {
                val dir = File(root, id).apply { mkdirs() }
                bin.renameTo(File(dir, "model.bin"))
                File(dir, ".ok").createNewFile()
            }
            ok.delete()
        }
    }
}
