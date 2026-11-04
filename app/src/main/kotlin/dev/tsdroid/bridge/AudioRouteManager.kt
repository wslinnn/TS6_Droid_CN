package dev.tsdroid.bridge

import android.annotation.SuppressLint
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import dev.tsdroid.data.AppLog as Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Capture/playback routing state surfaced to the UI. */
sealed class AudioRouteState {
    /** Proven media path: built-in speaker/A2DP playback, built-in mic capture. */
    data object Media : AudioRouteState()

    /** Communication route through a Bluetooth headset (HFP both directions). */
    data class Comm(val deviceName: String) : AudioRouteState()
}

/**
 * Bluetooth audio routing for the hybrid path.
 *
 * The default route is the proven media path (USAGE_GAME track +
 * VOICE_PERFORMANCE capture) — byte-identical behavior to the pre-routing
 * build. When a Bluetooth headset carrying a microphone (classic SCO or
 * BLE) shows up, the manager drives the communication route: mode switch,
 * explicit [AudioManager.setCommunicationDevice] assert, and SCO
 * establishment — the mic only exists as an input device once SCO is up.
 * On headset loss, SCO start timeout, or a frozen playback head (the
 * "dead track" fingerprint from the 2026-10-13 bisect), it reverts to the
 * media path.
 *
 * The manager only *decides and applies* mode/device plumbing; track and
 * recorder rebuilds live in the [Listener] (AudioBridge).
 *
 * @param listener route transitions are delivered here on the manager's
 *   coroutine; failures inside the listener never wedge the manager.
 */
class AudioRouteManager(
    private val audioManager: AudioManager,
    private val listener: Listener,
) {
    interface Listener {
        /**
         * The route changed and mode/device plumbing was applied. The
         * receiver must rebuild its track and recorder for the new route.
         * `comm=false` always means "back to the media path".
         */
        suspend fun onRouteChanged(comm: Boolean, deviceName: String?)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _routeState = MutableStateFlow<AudioRouteState>(AudioRouteState.Media)
    val routeState: StateFlow<AudioRouteState> = _routeState.asStateFlow()

    private var deviceCallback: AudioDeviceCallback? = null
    private var evaluateJob: Job? = null
    private var legacyScoReceiverRegistered = false

    /** Currently applied communication device (null on the media path). */
    private var appliedCommDevice: AudioDeviceInfo? = null

    /**
     * Suppresses communication-route re-entry after a failed attempt until
     * this deadline (elapsedRealtime). Prevents connect/disconnect flurries
     * from flapping the whole audio path.
     */
    @Volatile
    private var commBlockedUntil = 0L

    fun start() {
        if (deviceCallback != null) return
        deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                scheduleEvaluate()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                scheduleEvaluate()
            }
        }
        audioManager.registerAudioDeviceCallback(deviceCallback, null)
        // Catch headsets that were already connected before start()
        scheduleEvaluate()
        Log.i(TAG, "AudioRouteManager started (api=${Build.VERSION.SDK_INT})")
    }

    fun stop() {
        deviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        deviceCallback = null
        scope.cancel()
    }

    /**
     * Force a revert to the media path — the playback-loop's frozen-head
     * detector ("dead track" fingerprint) calls this. The communication
     * route stays blocked for [COMM_RETRY_COOLDOWN_MS] so the same headset
     * cannot immediately trap the path again.
     */
    fun forceMediaFallback(reason: String) {
        Log.w(TAG, "Route fallback requested: $reason")
        commBlockedUntil = SystemClock.elapsedRealtime() + COMM_RETRY_COOLDOWN_MS
        scheduleEvaluate(immediate = true)
    }

    /** Input device for the active SCO link, or null on the media path. */
    fun scoInputDevice(): AudioDeviceInfo? {
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }

    /** True while the communication route is applied (mode + comm device). */
    fun isCommRoute(): Boolean = appliedCommDevice != null

    /**
     * One device-callback may fire several times per Bluetooth connect;
     * collapse the burst into a single evaluation.
     */
    private fun scheduleEvaluate(immediate: Boolean = false) {
        synchronized(this) {
            evaluateJob?.cancel()
            evaluateJob = scope.launch {
                if (!immediate) delay(DEVICE_CHANGE_DEBOUNCE_MS)
                evaluate()
            }
        }
    }

    private suspend fun evaluate() {
        val btDevice = findBluetoothCommDevice()
        val now = SystemClock.elapsedRealtime()

        if (btDevice == null) {
            // No Bluetooth headset with a mic — the media path is the only
            // valid route; leave it if we were on comm
            if (appliedCommDevice != null) {
                Log.i(TAG, "Bluetooth headset gone; reverting to media route")
                exitCommRoute()
                deliverRoute(comm = false, deviceName = null)
            }
            return
        }

        if (appliedCommDevice != null) return // already on comm
        if (now < commBlockedUntil) {
            Log.i(
                TAG,
                "Bluetooth headset present but comm route blocked for " +
                    "${commBlockedUntil - now}ms (recent failure)",
            )
            return
        }

        val name = btDevice.productName?.toString().orEmpty()
            .ifEmpty { "Bluetooth headset" }
        Log.i(TAG, "Bluetooth headset connected (${btDevice.typeName()}): entering comm route")
        if (!enterCommRoute(btDevice)) {
            Log.w(TAG, "Comm route failed (SCO did not come up); staying on media path")
            commBlockedUntil = now + COMM_RETRY_COOLDOWN_MS
            return
        }
        deliverRoute(comm = true, deviceName = name)
    }

    private suspend fun deliverRoute(comm: Boolean, deviceName: String?) {
        try {
            listener.onRouteChanged(comm, deviceName)
        } catch (t: Throwable) {
            Log.e(TAG, "Route listener failed (comm=$comm); reverting plumbing", t)
            if (comm) exitCommRoute()
        }
    }

    /** Mode + comm-device plumbing for the communication route. */
    @SuppressLint("MissingPermission")
    private suspend fun enterCommRoute(device: AudioDeviceInfo): Boolean {
        return try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (!audioManager.setCommunicationDevice(device) ||
                    audioManager.communicationDevice?.id != device.id
                ) {
                    Log.w(TAG, "setCommunicationDevice did not stick to ${device.typeName()}")
                    exitCommRoute()
                    return false
                }
            } else {
                // Legacy path (API 29/30): request SCO directly and confirm
                // the SCO input device materializes below.
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            }
            appliedCommDevice = device
            if (!waitForScoInput(SCO_START_TIMEOUT_MS)) {
                Log.w(TAG, "SCO input device did not appear within ${SCO_START_TIMEOUT_MS}ms")
                exitCommRoute()
                return false
            }
            Log.i(TAG, "Comm route up: mode=IN_COMMUNICATION dev=${device.typeName()}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "enterCommRoute failed", t)
            exitCommRoute()
            false
        }
    }

    private fun exitCommRoute() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "exitCommRoute plumbing failed (continuing)", t)
        }
        audioManager.mode = AudioManager.MODE_NORMAL
        appliedCommDevice = null
    }

    /** Poll for the SCO input device; it only exists once SCO is established. */
    private suspend fun waitForScoInput(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (scoInputDevice() != null) return true
            delay(200)
        }
        return scoInputDevice() != null
    }

    /**
     * A Bluetooth headset usable as a communication device: classic SCO
     * (HFP) or BLE. A2DP-only sinks never appear here, which is exactly the
     * "no microphone on this headset" case.
     */
    private fun findBluetoothCommDevice(): AudioDeviceInfo? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
        } else {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        }
    }

    private fun AudioDeviceInfo.typeName(): String = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BLUETOOTH_SCO"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE_HEADSET"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HEADSET"
        else -> "type=$type"
    }

    companion object {
        private const val TAG = "AudioRoute"

        /** Device callbacks arrive in bursts during BT connect. */
        private const val DEVICE_CHANGE_DEBOUNCE_MS = 1_500L

        /** SCO must materialize as an input device within this window. */
        private const val SCO_START_TIMEOUT_MS = 5_000L

        /** Cooldown after a failed comm attempt before trying the same headset again. */
        private const val COMM_RETRY_COOLDOWN_MS = 10_000L
    }
}
