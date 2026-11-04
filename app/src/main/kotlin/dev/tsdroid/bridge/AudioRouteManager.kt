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
import kotlinx.coroutines.isActive
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
    @Volatile
    private var appliedCommDevice: AudioDeviceInfo? = null

    /** Drift watch over the communication route (device self-reverts). */
    private var commWatchJob: Job? = null

    /**
     * Suppresses communication-route re-entry after a failed attempt until
     * this deadline (elapsedRealtime). Prevents connect/disconnect flurries
     * from flapping the whole audio path.
     */
    @Volatile
    private var commBlockedUntil = 0L

    /**
     * Voice-call stream volume saved when entering the communication route.
     * MODE_IN_COMMUNICATION moves playback onto the voice-call stream, and a
     * phone that never made calls keeps that slider at 0 — the whole session
     * goes silent in the headset while capture still works. Lifted from zero
     * on entry, restored on exit; a non-zero user setting is never touched.
     */
    private var savedCallVolume = -1

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
     * detector ("dead track" fingerprint) and the comm-device drift watch
     * call this. The communication route stays blocked for
     * [COMM_RETRY_COOLDOWN_MS] so the same headset cannot immediately trap
     * the path again.
     */
    fun forceMediaFallback(reason: String) {
        Log.w(TAG, "Route fallback requested: $reason")
        commBlockedUntil = SystemClock.elapsedRealtime() + COMM_RETRY_COOLDOWN_MS
        // evaluate() alone would early-return while the comm route is
        // applied; leave it explicitly and notify the pipeline.
        scope.launch {
            if (appliedCommDevice != null) {
                exitCommRoute()
                deliverRoute(comm = false, deviceName = null)
            }
        }
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
        // Publish BEFORE notifying so the UI never lags the actual plumbing;
        // the listener rebuilds track/recorder for the new route
        _routeState.value = if (comm) {
            AudioRouteState.Comm(deviceName ?: "Bluetooth headset")
        } else {
            AudioRouteState.Media
        }
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
                // The framework applies the communication device
                // asynchronously and the device object itself can be
                // refreshed mid-connect (ids change), so a single call plus
                // an instant id read-back is NOT a reliable verdict — seen
                // as "did not stick" ~300ms after HFP connect on API 37.
                // Retry with fresh lookups and poll the read-back instead.
                var applied: AudioDeviceInfo? = null
                var attempt = 0
                while (applied == null && attempt < SET_COMM_MAX_ATTEMPTS) {
                    attempt++
                    val target = findBluetoothCommDevice() ?: break // headset gone
                    if (!audioManager.setCommunicationDevice(target)) {
                        Log.w(TAG, "setCommunicationDevice rejected (attempt $attempt)")
                    } else {
                        val deadline = SystemClock.elapsedRealtime() + COMM_STICK_POLL_MS
                        while (SystemClock.elapsedRealtime() < deadline) {
                            if (audioManager.communicationDevice?.id == target.id) {
                                applied = target
                                break
                            }
                            delay(200)
                        }
                    }
                    if (applied == null) delay(SET_COMM_RETRY_DELAY_MS)
                }
                if (applied == null) {
                    Log.w(TAG, "setCommunicationDevice did not stick after $attempt attempts")
                    exitCommRoute()
                    return false
                }
                appliedCommDevice = applied
            } else {
                // Legacy path (API 29/30): request SCO directly and confirm
                // the SCO input device materializes below.
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
                appliedCommDevice = device
            }
            if (!waitForScoInput(SCO_START_TIMEOUT_MS)) {
                Log.w(TAG, "SCO input device did not appear within ${SCO_START_TIMEOUT_MS}ms")
                exitCommRoute()
                return false
            }
            ensureCallVolumeNotMuted()
            startCommWatch()
            Log.i(TAG, "Comm route up: mode=IN_COMMUNICATION dev=${device.typeName()}")
            true
        } catch (t: Throwable) {
            Log.e(TAG, "enterCommRoute failed", t)
            exitCommRoute()
            false
        }
    }

    private fun exitCommRoute() {
        commWatchJob?.cancel()
        commWatchJob = null
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
        restoreCallVolume()
        audioManager.mode = AudioManager.MODE_NORMAL
        appliedCommDevice = null
    }

    /**
     * The communication device is not sticky on several ROMs: after the
     * route is established the framework can silently fall back to the
     * earpiece while the track keeps consuming normally — audible only as
     * "the headset plays nothing", with no frozen-head fingerprint. Re-assert
     * when the device or the SCO link drifts, and give up to the media path
     * after repeated failures.
     */
    private fun startCommWatch() {
        commWatchJob?.cancel()
        commWatchJob = scope.launch {
            var lostStreak = 0
            while (isActive) {
                delay(COMM_DEVICE_WATCH_INTERVAL_MS)
                val applied = appliedCommDevice ?: return@launch
                val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    audioManager.communicationDevice
                } else {
                    applied // legacy path has no readable communication device
                }
                val scoPresent = scoInputDevice() != null
                if (current?.id == applied.id && scoPresent) {
                    lostStreak = 0
                    continue
                }
                lostStreak++
                Log.w(
                    TAG,
                    "Comm route drift (dev=${current?.let { it.typeName() } ?: "none"}, " +
                        "sco=$scoPresent, streak=$lostStreak)",
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val fresh = findBluetoothCommDevice()
                    if (fresh != null &&
                        audioManager.setCommunicationDevice(fresh) &&
                        audioManager.communicationDevice?.id == fresh.id
                    ) {
                        appliedCommDevice = fresh
                        lostStreak = 0
                        Log.i(TAG, "Comm device re-asserted (${fresh.typeName()})")
                        continue
                    }
                }
                if (lostStreak >= COMM_DEVICE_LOST_STREAK_LIMIT) {
                    forceMediaFallback("comm device lost (streak $lostStreak)")
                    return@launch
                }
            }
        }
    }

    /**
     * MODE_IN_COMMUNICATION routes playback through the voice-call volume
     * stream. Phones that never made calls keep that stream at 0, which
     * reads as a completely dead session; lift it once and restore the
     * user's value on exit. A non-zero setting belongs to the user — leave
     * it alone.
     */
    private fun ensureCallVolumeNotMuted() {
        try {
            val current = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
            Log.i(TAG, "Voice-call stream volume=$current/$max")
            // Lift near-muted values too: 1-2 steps out of 15 is inaudible
            // and reads as a dead headset, indistinguishable from zero
            if (max <= 0 || current >= (max * 3) / 10) return
            savedCallVolume = current
            val target = (max * 8) / 10
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0)
            Log.i(TAG, "Voice-call stream near-muted ($current/$max); raised to $target for the comm route")
        } catch (t: Throwable) {
            Log.w(TAG, "Voice-call volume lift failed (continuing)", t)
        }
    }

    private fun restoreCallVolume() {
        val saved = savedCallVolume
        savedCallVolume = -1
        if (saved < 0) return
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, saved, 0)
        } catch (t: Throwable) {
            Log.w(TAG, "Voice-call volume restore failed (continuing)", t)
        }
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

        /** setCommunicationDevice retry budget (async application + device-id refresh). */
        private const val SET_COMM_MAX_ATTEMPTS = 5

        /** How long one attempt polls the read-back before retrying. */
        private const val COMM_STICK_POLL_MS = 1_500L

        /** Pause between setCommunicationDevice attempts. */
        private const val SET_COMM_RETRY_DELAY_MS = 300L

        /** Drift watch cadence over an established communication route. */
        private const val COMM_DEVICE_WATCH_INTERVAL_MS = 2_000L

        /** Consecutive drift checks before abandoning the comm route. */
        private const val COMM_DEVICE_LOST_STREAK_LIMIT = 3

        /** Cooldown after a failed comm attempt before trying the same headset again. */
        private const val COMM_RETRY_COOLDOWN_MS = 10_000L
    }
}
