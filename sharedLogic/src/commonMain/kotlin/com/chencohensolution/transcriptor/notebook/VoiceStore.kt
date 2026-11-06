package com.chencohensolution.transcriptor.notebook

import com.chencohensolution.transcriptor.models.ModelCatalog
import com.chencohensolution.transcriptor.speech.Voice
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/**
 * The voices a notebook knows: `notebooks/<notebook id>/voices.json` in the [place] the lectures are kept (the library
 * once there is one), a name and an average voice print per person the user named in one of its lectures, so the next
 * lectures label them by name. A person has a print per voice-print model (prints of different models don't compare);
 * [model] is the one in use: [load] gives its prints, and [save] files a print without a model under it. A voice without
 * a print of the model in use is learned again the next time the user names it.
 */
class VoiceStore(private val model: () -> String = { DEFAULT_MODEL }, private val place: () -> Place) {
    constructor(fileSystem: FileSystem, root: Path, model: () -> String = { DEFAULT_MODEL }) : this(model, { Place(fileSystem, root) })

    private val fileSystem: FileSystem get() = place().fileSystem
    private val root: Path get() = place().root
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun file(notebookId: String) = root / "notebooks" / notebookId / "voices.json"

    /** The voices with a print of the model in use. */
    fun load(notebookId: String): List<Voice> = all(notebookId).filter { it.model == model() }

    /** Every voice, a print per model. */
    fun all(notebookId: String): List<Voice> {
        val file = file(notebookId)
        if (!fileSystem.exists(file)) return emptyList()
        return json.decodeFromString<VoicesFileV1>(fileSystem.read(file) { readUtf8() }).voices
            .map { Voice(it.name, it.print, it.model.ifEmpty { DEFAULT_MODEL }) }
    }

    /** Adds or replaces (by name and model) a voice; one without a model is the model in use's. */
    fun save(notebookId: String, voice: Voice) {
        val v = voice.copy(model = voice.model.ifEmpty { model() })
        write(notebookId, all(notebookId).filter { it.name != v.name || it.model != v.model } + v)
    }

    /** Renames a voice (every model's print), as when the user renames a speaker the notebook already knew. */
    fun rename(notebookId: String, from: String, to: String) {
        val voices = all(notebookId)
        val moved = voices.filter { it.name == from }
        if (moved.isEmpty()) return
        val models = moved.map { it.model }.toSet()
        write(notebookId, voices.filter { it.name != from && !(it.name == to && it.model in models) } + moved.map { it.copy(name = to) })
    }

    private fun write(notebookId: String, voices: List<Voice>) {
        val file = file(notebookId)
        fileSystem.createDirectories(file.parent!!)
        val temp = file.parent!! / "voices.json.tmp"
        fileSystem.write(temp) { writeUtf8(json.encodeToString(VoicesFileV1(voices = voices.map { VoiceV1(it.name, it.print, it.model) }))) }
        fileSystem.atomicMove(temp, file)
    }

    companion object {
        /** The model of prints saved before voices had one: CAM++, the only model then. */
        val DEFAULT_MODEL = ModelCatalog.voicePrints.id
    }
}

@Serializable
private data class VoicesFileV1(val v: Int = 1, val voices: List<VoiceV1> = emptyList())

/** A voice's print by one model; [model] is empty in files written before there was a choice (CAM++). */
@Serializable
private data class VoiceV1(val name: String, val print: List<Float>, val model: String = "")
