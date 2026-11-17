package com.chencohensolution.transcriptor.recording

import kotlinx.serialization.Serializable

/**
 * A microphone the phone can record from besides its own: a Bluetooth headset or earbuds, a wired headset, a USB
 * microphone. [key] finds it again when it reconnects (its kind, its address and its name); [name] is what the phone
 * calls it. The phone's own microphones are the default (no [MicInput]): stereo, 48 kHz.
 */
@Serializable
data class MicInput(val key: String, val name: String, val kind: Kind) {
    enum class Kind {
        /** A Bluetooth headset (hands-free profile): mono, usually 16 kHz, with the headset's noise reduction. */
        Bluetooth,

        /** Bluetooth LE Audio earbuds. */
        BluetoothLe,
        Wired,
        Usb,

        /**
         * One of the phone's own microphones on its own ([MicInput.key] "builtin:<where>": bottom, back…), as the phone
         * lists them (owner's request, Nov 17, 2026); no [MicInput] is all of them, in stereo.
         */
        BuiltIn,
    }
}

/** The input a recording is actually using, as the phone routed it; null: the phone's own microphones. */
data class InputInUse(val name: String, val kind: MicInput.Kind)
