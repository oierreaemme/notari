package com.voicenotemd.core.asr

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log

/**
 * Routes audio capture to a connected Bluetooth headset mic (SCO / Hands-Free Profile) when
 * one is available, so in-car / hands-free dictation uses the earbuds rather than the phone
 * mic. Falls back silently to the phone mic when no BT device is present.
 *
 * Engine-agnostic: used by [BatchSpeechToTextSession] — it only manages input routing,
 * not the recogniser, so it is reusable by any future ASR backend.
 *
 * NOTE: a BT headset mic uses the narrowband Hands-Free Profile (≈8–16 kHz, compressed),
 * which caps transcription quality for ANY ASR engine. This router cannot improve the
 * underlying audio; it only ensures we capture from the headset.
 *
 * On API 31+ this uses [AudioManager.setCommunicationDevice] (the modern, fairly prompt
 * path). On 28–30 it falls back to the deprecated `startBluetoothSco()`. SCO connection is
 * asynchronous; a more robust version would wait for `ACTION_SCO_AUDIO_STATE_UPDATED =
 * CONNECTED` before reading — tracked as a refinement.
 */
class BluetoothAudioRouter(
    context: Context,
) {
    private val audioManager =
        context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var savedMode: Int = AudioManager.MODE_NORMAL
    private var routed = false

    /**
     * Route capture input to a BT SCO headset mic if one is connected. Returns true if
     * routing to Bluetooth succeeded, false if we stayed on the phone mic.
     */
    fun routeToBluetoothIfAvailable(): Boolean =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val btDevice =
                    audioManager.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                        ?: return logRoute(false, "no BT SCO device connected")
                savedMode = audioManager.mode
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                routed = audioManager.setCommunicationDevice(btDevice)
                logRoute(routed, "setCommunicationDevice(${btDevice.productName})")
            } else {
                @Suppress("DEPRECATION")
                if (!audioManager.isBluetoothScoAvailableOffCall) {
                    return logRoute(false, "SCO unavailable off-call")
                }
                savedMode = audioManager.mode
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                @Suppress("DEPRECATION")
                audioManager.startBluetoothSco()
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = true
                routed = true
                logRoute(true, "startBluetoothSco (legacy)")
            }
        }.getOrElse { logRoute(false, "exception: ${it.message}") }

    /** Restore audio routing/mode after capture ends. Safe to call when not routed. */
    fun clear() {
        if (!routed) return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                audioManager.stopBluetoothSco()
            }
            audioManager.mode = savedMode
        }
        routed = false
    }

    private fun logRoute(
        success: Boolean,
        detail: String,
    ): Boolean {
        Log.i(TAG, "routing=${if (success) "BLUETOOTH" else "phone-mic"} ($detail)")
        return success
    }

    private companion object {
        const val TAG = "AsrBtRouter"
    }
}
