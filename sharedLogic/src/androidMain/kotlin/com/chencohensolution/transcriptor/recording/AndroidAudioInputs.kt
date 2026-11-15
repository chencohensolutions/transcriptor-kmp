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

    /** The inputs connected now, Bluetooth first. */
    fun connected(): List<MicInput> =
        audio.getDevices(AudioManager.GET_DEVICES_INPUTS).mapNotNull { it.toInput() }.distinctBy { it.key }.sortedBy { it.kind.ordinal }

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

    companion object {
        /** [AudioDeviceInfo] as an input the user may pick; null for the phone's own and for what isn't a microphone. */
        fun AudioDeviceInfo.toInput(): MicInput? {
            if (!isSource) return null
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
