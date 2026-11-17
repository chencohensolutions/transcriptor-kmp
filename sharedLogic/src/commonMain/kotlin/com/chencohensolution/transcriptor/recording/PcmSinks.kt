package com.chencohensolution.transcriptor.recording

/** A [PcmSink] fed through a [PcmReducer] first: the microphone's 48 kHz stereo to what [sink] keeps. */
class ReducingSink(private val reducer: PcmReducer, private val sink: PcmSink) : PcmSink {
    override fun write(pcm: ByteArray, size: Int) {
        val r = reducer.process(pcm, size)
        sink.write(r.pcm, r.size)
    }

    override fun checkpoint() = sink.checkpoint()
    override fun close() = sink.close()
}
