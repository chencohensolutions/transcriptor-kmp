package com.chencohensolution.transcriptor.recording

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * The microphones connected to the phone besides its own ([MicInput]): Bluetooth headsets and earbuds, wired headsets,
 * USB microphones, from [AudioManager.getDevices]. The phone's own (built-in) microphones aren't listed: they are the
 * default.
 */
class AndroidAudioInputs(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)

    /** The inputs connected now: the phone's own microphones one by one first, then Bluetooth, wired, USB. */
    fun connected(): List<MicInput> =
        audio.getDevices(AudioManager.GET_DEVICES_INPUTS).mapNotNull { it.toInput() }.distinctBy { it.key }
            .sortedBy { if (it.kind == MicInput.Kind.BuiltIn) -1 else it.kind.ordinal }

    /** [input]'s device when it is connected now. */
    fun find(input: MicInput): AudioDeviceInfo? =
        audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.toInput()?.key == input.key }

    /** Calls [changed] whenever an input connects or disconnects (and once now); returns the call that stops it. */
    fun watch(changed: (List<MicInput>) -> Unit): () -> Unit {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = changed(connected())
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = changed(connected())
        }
        audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        return { audio.unregisterAudioDeviceCallback(callback) }
    }

    /**
     * Routes the phone's calls audio to [device] for a Bluetooth input: a hands-free headset's microphone only opens as
     * the communication device. Returns false when the phone refused.
     */
    fun useForCommunication(device: AudioDeviceInfo): Boolean = audio.setCommunicationDevice(device)

    fun clearCommunication() = audio.clearCommunicationDevice()

    /**
     * [input] (or, null or not connected, the phone's own microphone) gives more than 16 bits: one of its encodings is
     * 24 bit, 32 bit or floating point. The S25's own microphones list 16 bit only; a USB microphone lists what it has.
     */
    fun deepCapture(input: MicInput?): Boolean {
        val device = input?.let { find(it) } ?: audio.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        return device?.encodings?.any { it in DEEP } == true
    }

    /** What the phone says about its own microphones: where each is, how it hears ([MicDetail]); empty when it says nothing. */
    fun builtInDetails(): List<MicDetail> = runCatching {
        audio.microphones.filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }.map { m ->
            val p = m.position.takeIf { it != android.media.MicrophoneInfo.POSITION_UNKNOWN }
            MicDetail(
                address = m.address.orEmpty(),
                x = p?.x, y = p?.y, z = p?.z,
                directionality = when (m.directionality) {
                    android.media.MicrophoneInfo.DIRECTIONALITY_OMNI -> "omni"
                    android.media.MicrophoneInfo.DIRECTIONALITY_BI_DIRECTIONAL -> "bidirectional"
                    android.media.MicrophoneInfo.DIRECTIONALITY_CARDIOID -> "cardioid"
                    android.media.MicrophoneInfo.DIRECTIONALITY_HYPER_CARDIOID -> "hypercardioid"
                    android.media.MicrophoneInfo.DIRECTIONALITY_SUPER_CARDIOID -> "supercardioid"
                    else -> null
                },
                sensitivityDb = m.sensitivity.takeIf { it != android.media.MicrophoneInfo.SENSITIVITY_UNKNOWN },
                maxSplDb = m.maxSpl.takeIf { it != android.media.MicrophoneInfo.SPL_UNKNOWN },
            )
        }
    }.getOrDefault(emptyList())

    /** The phone records [android.media.MediaRecorder.AudioSource.UNPROCESSED]: the microphone without voice processing. */
    val unprocessed: Boolean get() = audio.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

    companion object {
        private val DEEP = setOf(
            android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED, android.media.AudioFormat.ENCODING_PCM_32BIT,
            android.media.AudioFormat.ENCODING_PCM_FLOAT,
        )
        /** [AudioDeviceInfo] as an input the user may pick; null for the phone's own and for what isn't a microphone. */
        fun AudioDeviceInfo.toInput(): MicInput? {
            if (!isSource) return null
            // The phone's own microphones by where they are ("bottom", "back"): the address the phone gives them.
            if (type == AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                val where = address?.takeIf { it.isNotBlank() } ?: return null
                return MicInput("builtin:$where", where, MicInput.Kind.BuiltIn)
            }
            val kind = kindOf(type) ?: return null
            val name = productName?.toString()?.takeIf { it.isNotBlank() } ?: return null
            return MicInput("${kind.name.lowercase()}:$address:$name", name, kind)
        }

        fun kindOf(type: Int): MicInput.Kind? = when (type) {
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> MicInput.Kind.Bluetooth
            AudioDeviceInfo.TYPE_BLE_HEADSET -> MicInput.Kind.BluetoothLe
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> MicInput.Kind.Wired
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> MicInput.Kind.Usb
            else -> null
        }
    }
}
