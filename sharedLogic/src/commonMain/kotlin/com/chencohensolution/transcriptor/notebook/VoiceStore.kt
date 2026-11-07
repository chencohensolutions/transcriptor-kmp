package com.chencohensolution.transcriptor.notebook

import com.chencohensolution.transcriptor.models.ModelCatalog
import com.chencohensolution.transcriptor.speech.Voice
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/**
 * The voices the app knows: `voices.json` in the [place] the moments are kept (the library once there is one), a name
 * and an average voice print per person the user named in any moment, so the next moments label them by name. A person has a print per voice-print model (prints of different models don't compare);
 * [model] is the one in use: [load] gives its prints, and [save] files a print without a model under it. A voice without
 * a print of the model in use is learned again the next time the user names it.
 */
class VoiceStore(private val model: () -> String = { DEFAULT_MODEL }, private val place: () -> Place) {
    constructor(fileSystem: FileSystem, root: Path, model: () -> String = { DEFAULT_MODEL }) : this(model, { Place(fileSystem, root) })

    private val fileSystem: FileSystem get() = place().fileSystem
    private val root: Path get() = place().root
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private val file get() = root / FILE

    /** The voices with a print of the model in use. */
    fun load(): List<Voice> = all().filter { it.model == model() }

    /** Every voice, a print per model. */
    fun all(): List<Voice> {
        val file = file
        if (!fileSystem.exists(file)) return emptyList()
        return json.decodeFromString<VoicesFileV1>(fileSystem.read(file) { readUtf8() }).voices
            .map { Voice(it.name, it.print, it.model.ifEmpty { DEFAULT_MODEL }) }
    }

    /** Adds or replaces (by name and model) a voice; one without a model is the model in use's. */
    fun save(voice: Voice) {
        val v = voice.copy(model = voice.model.ifEmpty { model() })
        write(all().filter { it.name != v.name || it.model != v.model } + v)
    }

    /** Renames a voice (every model's print), as when the user renames a speaker the app already knew. */
    fun rename(from: String, to: String) {
        val voices = all()
        val moved = voices.filter { it.name == from }
        if (moved.isEmpty()) return
        val models = moved.map { it.model }.toSet()
        write(voices.filter { it.name != from && !(it.name == to && it.model in models) } + moved.map { it.copy(name = to) })
    }

    /** Adds voices kept elsewhere (a notebook's, when notebooks became labels): those it lacks by name and model. */
    internal fun add(voices: List<Voice>) {
        val here = all()
        val added = voices.filter { v -> here.none { it.name == v.name && it.model == v.model } }
        if (added.isNotEmpty()) write(here + added)
    }

    private fun write(voices: List<Voice>) {
        val file = file
        fileSystem.createDirectories(file.parent!!)
        val temp = file.parent!! / "$FILE.tmp"
        fileSystem.write(temp) { writeUtf8(json.encodeToString(VoicesFileV1(voices = voices.map { VoiceV1(it.name, it.print, it.model) }))) }
        fileSystem.atomicMove(temp, file)
    }

    companion object {
        const val FILE = "voices.json"

        /** The model of prints saved before voices had one: CAM++, the only model then. */
        val DEFAULT_MODEL = ModelCatalog.voicePrints.id
    }
}

@Serializable
private data class VoicesFileV1(val v: Int = 1, val voices: List<VoiceV1> = emptyList())

/** A voice's print by one model; [model] is empty in files written before there was a choice (CAM++). */
@Serializable
private data class VoiceV1(val name: String, val print: List<Float>, val model: String = "")
