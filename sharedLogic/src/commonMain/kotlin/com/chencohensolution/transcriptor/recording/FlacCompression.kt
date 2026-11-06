package com.chencohensolution.transcriptor.recording

import com.chencohensolution.transcriptor.notebook.AUDIO_FLAC
import com.chencohensolution.transcriptor.notebook.AUDIO_WAV
import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.LectureStore
import com.chencohensolution.transcriptor.settings.FLAC_DEFAULT_LEVEL
import okio.FileSystem
import okio.Path

/**
 * Encodes a WAV file to FLAC at [level] (0 the fastest, 8 the smallest), reporting progress 0..1, and returns the
 * number of frames (samples per channel) written.
 */
fun interface FlacEncode {
    fun encode(wav: Path, flac: Path, level: Int, progress: (Float) -> Unit): Long
}

/**
 * Compresses a lecture recorded with "WAV, then FLAC": the WAV to `audio.flac.tmp`, checked to hold every frame,
 * renamed to `audio.flac`, `lecture.json` pointed at it, then the WAV deleted. Interrupted, the WAV stays and the
 * lecture stays pending, so [compress] runs again from the start.
 */
class FlacCompression(
    private val fileSystem: FileSystem,
    private val store: LectureStore,
    private val encoder: FlacEncode,
    /** The compression level, read when each lecture is compressed (the setting can change in between). */
    private val level: () -> Int = { FLAC_DEFAULT_LEVEL },
) {
    fun pending(): List<Lecture> = store.all().filter { it.pendingFlac }

    fun compress(lecture: Lecture, progress: (Float) -> Unit = {}): Lecture {
        val wav = store.audioPath(lecture, AUDIO_WAV)
        val flac = store.audioPath(lecture, AUDIO_FLAC)
        val temp = store.audioPath(lecture, "$AUDIO_FLAC.tmp")
        fileSystem.delete(temp)
        WavWriter.repair(fileSystem, wav)
        val expected = WavWriter.read(fileSystem, wav).frames
        val written = encoder.encode(wav, temp, level(), progress)
        check(written == expected) { "FLAC has $written frames, the WAV $expected" }
        fileSystem.atomicMove(temp, flac)
        val done = lecture.copy(audioFile = AUDIO_FLAC, pendingFlac = false)
        store.save(done)
        fileSystem.delete(wav)
        return done
    }
}
