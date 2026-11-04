package com.chencohensolution.transcriptor.notebook

import com.chencohensolution.transcriptor.speech.Voice
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/**
 * The voices a notebook knows: `notebooks/<notebook id>/voices.json` in the [place] the lectures are kept (the library
 * once there is one), a name and an average voice print per person the user named in one of its lectures, so the next
 * lectures label them by name.
 */
class VoiceStore(private val place: () -> Place) {
    constructor(fileSystem: FileSystem, root: Path) : this({ Place(fileSystem, root) })

    private val fileSystem: FileSystem get() = place().fileSystem
    private val root: Path get() = place().root
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private fun file(notebookId: String) = root / "notebooks" / notebookId / "voices.json"

    fun load(notebookId: String): List<Voice> {
        val file = file(notebookId)
        if (!fileSystem.exists(file)) return emptyList()
        return json.decodeFromString<VoicesFileV1>(fileSystem.read(file) { readUtf8() }).voices.map { Voice(it.name, it.print) }
    }

    /** Adds or replaces (by name) a voice. */
    fun save(notebookId: String, voice: Voice) {
        val voices = load(notebookId).filter { it.name != voice.name } + voice
        val file = file(notebookId)
        fileSystem.createDirectories(file.parent!!)
        val temp = file.parent!! / "voices.json.tmp"
        fileSystem.write(temp) { writeUtf8(json.encodeToString(VoicesFileV1(voices = voices.map { VoiceV1(it.name, it.print) }))) }
        fileSystem.atomicMove(temp, file)
    }

    /** Renames a voice, as when the user renames a speaker the notebook already knew. */
    fun rename(notebookId: String, from: String, to: String) {
        val voice = load(notebookId).firstOrNull { it.name == from } ?: return
        val file = file(notebookId)
        val voices = load(notebookId).filter { it.name != from && it.name != to } + voice.copy(name = to)
        val temp = file.parent!! / "voices.json.tmp"
        fileSystem.write(temp) { writeUtf8(json.encodeToString(VoicesFileV1(voices = voices.map { VoiceV1(it.name, it.print) }))) }
        fileSystem.atomicMove(temp, file)
    }
}

@Serializable
private data class VoicesFileV1(val v: Int = 1, val voices: List<VoiceV1> = emptyList())

@Serializable
private data class VoiceV1(val name: String, val print: List<Float>)
