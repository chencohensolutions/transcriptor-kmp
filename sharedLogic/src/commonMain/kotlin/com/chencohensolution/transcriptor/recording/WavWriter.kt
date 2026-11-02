package com.chencohensolution.transcriptor.recording

import okio.Buffer
import okio.FileHandle
import okio.FileSystem
import okio.Path
import okio.use

/**
 * A WAV file (PCM, 16 bit) written as the PCM arrives. Its header's sizes are only right after [checkpoint] or
 * [close]; [repair] fixes them from the file's length after a crash.
 */
class WavWriter(
    fileSystem: FileSystem,
    path: Path,
    private val sampleRate: Int = SAMPLE_RATE,
    private val channels: Int = CHANNELS,
) : PcmSink {
    private val handle: FileHandle = fileSystem.openReadWrite(path)
    private var dataBytes = 0L

    init {
        handle.resize(0)
        val header = header(sampleRate, channels, 0)
        handle.write(0, header, 0, header.size)
    }

    override fun write(pcm: ByteArray, size: Int) {
        handle.write(HEADER_SIZE + dataBytes, pcm, 0, size)
        dataBytes += size
    }

    override fun checkpoint() {
        writeSizes(handle, dataBytes)
        handle.flush()
    }

    override fun close() {
        checkpoint()
        handle.close()
    }

    /** What a WAV file's header says. */
    data class Info(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val dataBytes: Long) {
        val frames get() = dataBytes / (channels * bitsPerSample / 8)
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val HEADER_SIZE = 44L

        fun header(sampleRate: Int, channels: Int, dataBytes: Long): ByteArray {
            val blockAlign = channels * 2
            return Buffer()
                .writeUtf8("RIFF").writeIntLe((36 + dataBytes).toInt()).writeUtf8("WAVE")
                .writeUtf8("fmt ").writeIntLe(16).writeShortLe(1).writeShortLe(channels)
                .writeIntLe(sampleRate).writeIntLe(sampleRate * blockAlign).writeShortLe(blockAlign).writeShortLe(16)
                .writeUtf8("data").writeIntLe(dataBytes.toInt())
                .readByteArray()
        }

        fun read(fileSystem: FileSystem, path: Path): Info = fileSystem.openReadOnly(path).use { handle ->
            val b = Buffer()
            handle.read(0, b, HEADER_SIZE)
            b.skip(22)
            val channels = b.readShortLe().toInt()
            val sampleRate = b.readIntLe()
            b.skip(6)
            val bits = b.readShortLe().toInt()
            b.skip(4)
            Info(sampleRate, channels, bits, b.readIntLe().toLong() and 0xFFFFFFFFL)
        }

        /** Sets the header's sizes from the file's length (whole frames); true when they were wrong. */
        fun repair(fileSystem: FileSystem, path: Path): Boolean {
            val info = read(fileSystem, path)
            val blockAlign = info.channels * info.bitsPerSample / 8
            val length = fileSystem.metadata(path).size ?: return false
            val dataBytes = (length - HEADER_SIZE) / blockAlign * blockAlign
            if (dataBytes == info.dataBytes) return false
            fileSystem.openReadWrite(path).use { writeSizes(it, dataBytes) }
            return true
        }

        private fun writeSizes(handle: FileHandle, dataBytes: Long) {
            val riff = Buffer().writeIntLe((36 + dataBytes).toInt()).readByteArray()
            val data = Buffer().writeIntLe(dataBytes.toInt()).readByteArray()
            handle.write(4, riff, 0, 4)
            handle.write(40, data, 0, 4)
        }
    }
}
