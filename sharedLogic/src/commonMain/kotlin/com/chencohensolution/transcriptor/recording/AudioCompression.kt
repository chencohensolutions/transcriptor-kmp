package com.chencohensolution.transcriptor.recording

import com.chencohensolution.transcriptor.notebook.AUDIO_AAC
import com.chencohensolution.transcriptor.notebook.AUDIO_FLAC
import com.chencohensolution.transcriptor.notebook.AUDIO_WAV
import com.chencohensolution.transcriptor.notebook.Lecture
import com.chencohensolution.transcriptor.notebook.LectureStore
import com.chencohensolution.transcriptor.notebook.pendingEncode
import com.chencohensolution.transcriptor.settings.AacSettings
import com.chencohensolution.transcriptor.settings.FLAC_DEFAULT_LEVEL
import com.chencohensolution.transcriptor.settings.FlacFormat
import okio.FileSystem
import okio.Path

/**
 * Encodes a WAV file to FLAC at [level] (0 the fastest, 8 the smallest) at the WAV's own rate, channels and bit depth
 * (owner's call, Nov 17, 2026: the WAV was recorded at the sound to keep), reporting progress 0..1, and returns the number of the WAV's frames (samples per channel) it took in.
 */
fun interface FlacEncode {
    fun encode(wav: Path, flac: Path, level: Int, format: FlacFormat, progress: (Float) -> Unit): Long
}

/**
 * Encodes a WAV file to one of the other codecs ([OtherCodec]: Opus, xHE-AAC, AMR-WB, AMR-NB), reporting progress 0..1,
 * and returns the number of the WAV's frames it took in.
 */
fun interface OtherEncode {
    fun encode(wav: Path, out: Path, spec: com.chencohensolution.transcriptor.settings.OtherCodec, progress: (Float) -> Unit): Long
}

/**
 * Encodes a WAV file to AAC (ADTS) as [settings] say, reporting progress 0..1, and returns the number of the WAV's frames
 * (samples per channel, at 48 kHz) it took in.
 */
fun interface AacEncode {
    fun encode(wav: Path, aac: Path, settings: AacSettings, progress: (Float) -> Unit): Long
}

/**
 * Encodes a lecture recorded with "WAV, then FLAC" or "WAV, then AAC": the WAV to `audio.flac.tmp` or `audio.aac.tmp`,
 * checked to hold every frame, renamed, `lecture.json` pointed at it, then the WAV deleted. Interrupted, the WAV
 * stays and the lecture stays pending, so [compress] runs again from the start.
 */
class AudioCompression(
    private val fileSystem: FileSystem,
    private val store: LectureStore,
    private val encoder: FlacEncode,
    /**
     * The compression level, read when each lecture is compressed, for the profile it was recorded with
     * ([Lecture.profile]; null: before profiles).
     */
    private val level: (profile: String?) -> Int = { FLAC_DEFAULT_LEVEL },
    private val aacEncoder: AacEncode? = null,
    /** The AAC settings, read when each lecture is encoded, for its profile. */
    private val aac: (profile: String?) -> AacSettings = { AacSettings() },
    /** FLAC's rate and channels, read when each lecture is encoded, for its profile. */
    private val flac: (profile: String?) -> FlacFormat = { FlacFormat() },
    private val otherEncoder: OtherEncode? = null,
    /** How [Lecture.pendingCodec] encodes for the lecture's profile. */
    private val other: (profile: String?, codec: com.chencohensolution.transcriptor.settings.Codec) -> com.chencohensolution.transcriptor.settings.OtherCodec? = { _, _ -> null },
) {
    fun pending(): List<Lecture> = store.index().filter { it.pendingEncode }.mapNotNull { store.load(it.id) }
        .filter { it.pendingEncode && (it.pendingFlac || (it.pendingAac && aacEncoder != null) || (it.pendingCodec != null && otherEncoder != null)) }

    fun compress(lecture: Lecture, progress: (Float) -> Unit = {}): Lecture {
        val otherCodec = com.chencohensolution.transcriptor.settings.Codec.of(lecture.pendingCodec)?.takeIf { !lecture.pendingFlac && !lecture.pendingAac }
        val toAac = lecture.pendingAac && !lecture.pendingFlac
        val name = otherCodec?.file ?: if (toAac) AUDIO_AAC else AUDIO_FLAC
        val wav = store.audioPath(lecture, AUDIO_WAV)
        val out = store.audioPath(lecture, name)
        val temp = store.audioPath(lecture, "$name.tmp")
        fileSystem.delete(temp)
        WavWriter.repair(fileSystem, wav)
        val expected = WavWriter.read(fileSystem, wav).frames
        val written = when {
            otherCodec != null -> checkNotNull(otherEncoder) { "No encoder for ${otherCodec.key}" }
                .encode(wav, temp, checkNotNull(other(lecture.profile, otherCodec)) { "No settings for ${otherCodec.key}" }, progress)
            toAac -> checkNotNull(aacEncoder) { "No AAC encoder" }.encode(wav, temp, aac(lecture.profile).valid(), progress)
            else -> encoder.encode(wav, temp, level(lecture.profile), flac(lecture.profile).valid(), progress)
        }
        check(written == expected) { "${otherCodec?.key ?: if (toAac) "AAC" else "FLAC"} took $written frames, the WAV has $expected" }
        fileSystem.atomicMove(temp, out)
        val done = lecture.copy(audioFile = name, pendingFlac = false, pendingAac = false, pendingCodec = null)
        store.save(done)
        fileSystem.delete(wav)
        return done
    }
}
