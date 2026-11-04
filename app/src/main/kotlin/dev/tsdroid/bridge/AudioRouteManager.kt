package dev.tsdroid.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
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
    private val context: Context,
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

        /**
         * Called right before the communication route is applied: the
         * receiver must stop media playback so A2DP is idle while SCO starts.
         * A headset still receiving A2DP audio keeps its audio path in music
         * mode and will not switch to HFP — SCO connects but stays silent.
         */
        suspend fun onCommRoutePrepare() {}

        /** The comm route did not come up: rebuild the media path. */
        suspend fun onCommRouteAborted() {}
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _routeState = MutableStateFlow<AudioRouteState>(AudioRouteState.Media)
    val routeState: StateFlow<AudioRouteState> = _routeState.asStateFlow()

    private var deviceCallback: AudioDeviceCallback? = null
    private var evaluateJob: Job? = null
    private var legacyScoReceiverRegistered = false

    /**
     * Latest SCO audio state reported by the framework. On Android 12+ the
     * SCO device is enumerated as soon as HFP connects, so "a SCO device
     * exists" is not proof that SCO audio is up — only CONNECTED (or the
     * platform's own `isBluetoothScoOn` flag) is.
     */
    @Volatile
    private var scoAudioState = AudioManager.SCO_AUDIO_STATE_DISCONNECTED
    /** False until the framework reports (or the sticky broadcast carries) any SCO state. */
    @Volatile
    private var scoStateSeen = false
    private var scoReceiver: BroadcastReceiver? = null

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

    /** Speakerphone state saved when entering the comm route (legacy trio). */
    private var savedSpeakerphoneOn: Boolean? = null

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
        registerScoReceiver()
        // Catch headsets that were already connected before start()
        scheduleEvaluate()
        Log.i(
            TAG,
            "AudioRouteManager started (api=${Build.VERSION.SDK_INT}, " +
                "btConnectPerm=${hasBluetoothConnectPermission()}, sco=${scoStateName(scoAudioState)})",
        )
    }

    fun stop() {
        deviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        deviceCallback = null
        unregisterScoReceiver()
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

    /**
     * Re-assert mode + communication device right before the track rebuild:
     * the ROM can silently reset the mode during the SCO setup window, and a
     * track created afterwards lands on the media stream (volume keys follow
     * media, voice stream never plays). Called by the route listener.
     */
    fun reassertCommPlumbing() {
        val applied = appliedCommDevice ?: return
        val previousMode = audioManager.mode
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val current = audioManager.communicationDevice
            if (current?.id != applied.id) {
                val fresh = findBluetoothCommDevice()
                if (fresh != null && audioManager.setCommunicationDevice(fresh)) {
                    appliedCommDevice = fresh
                    Log.i(TAG, "Comm device re-asserted before track rebuild (${fresh.typeName()})")
                } else {
                    Log.w(TAG, "Comm device re-assert failed before track rebuild")
                }
            }
        }
        silenceSpeakerphone()
        requestLegacySco("reassert")
        Log.i(TAG, "Comm plumbing re-asserted before track rebuild (mode $previousMode -> IN_COMMUNICATION)")
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
        if (!hasBluetoothConnectPermission()) {
            // API 31+: with BLUETOOTH_CONNECT denied some ROMs still accept
            // setCommunicationDevice() and then refuse to start SCO for this
            // uid — the route reads back as applied while no audio flows.
            // Never enter a route we cannot verify.
            if (appliedCommDevice != null) {
                Log.w(TAG, "Bluetooth permission lost; reverting to media route")
                exitCommRoute()
                deliverRoute(comm = false, deviceName = null)
            }
            Log.e(TAG, "BLUETOOTH_CONNECT not granted; staying on the media route")
            return
        }
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
        // A2DP must be idle before SCO starts, otherwise the headset never
        // leaves music mode and the SCO audio path stays down
        try {
            listener.onCommRoutePrepare()
        } catch (t: Throwable) {
            Log.w(TAG, "Comm route prepare failed (continuing)", t)
        }
        if (!enterCommRoute(btDevice)) {
            Log.w(TAG, "Comm route failed (SCO did not come up); staying on media path")
            try {
                listener.onCommRouteAborted()
            } catch (t: Throwable) {
                Log.w(TAG, "Media restore after failed comm route failed", t)
            }
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
            // Give the audio policy a moment to tear the A2DP patch down
            // after the listener released the media track
            delay(COMM_PREPARE_SETTLE_MS)
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            silenceSpeakerphone()
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
                // Legacy path (API 29/30): no readable communication device
                appliedCommDevice = device
            }
            // Legacy SCO request on every API level, mirroring the path older
            // VoIP apps use on this ROM class: startBluetoothSco() makes this
            // app the SCO client and setBluetoothScoOn(true) forces the
            // communication strategy to SCO. setCommunicationDevice() alone
            // has been observed to connect SCO without starting its audio
            // stream (SCO CONNECTED, track routed to type 7, head frozen).
            requestLegacySco("enter")
            if (!waitForScoConnection(SCO_START_TIMEOUT_MS)) {
                Log.w(TAG, "SCO did not connect within ${SCO_START_TIMEOUT_MS}ms; staying on media path")
                exitCommRoute()
                return false
            }
            ensureCallVolumeNotMuted()
            startCommWatch()
            Log.i(
                TAG,
                "Comm route up: mode=IN_COMMUNICATION dev=${device.typeName()} " +
                    "sco=${scoStateName(scoAudioState)}",
            )
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
            }
        } catch (t: Throwable) {
            Log.w(TAG, "exitCommRoute plumbing failed (continuing)", t)
        }
        stopLegacySco()
        restoreSpeakerphone()
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
                val scoPresent = scoInputDevice() != null && isScoConnected()
                if (current?.id == applied.id && scoPresent) {
                    lostStreak = 0
                    continue
                }
                lostStreak++
                Log.w(
                    TAG,
                    "Comm route drift (dev=${current?.let { it.typeName() } ?: "none"}, " +
                        "sco=${scoStateName(scoAudioState)}, up=$scoPresent, streak=$lostStreak)",
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

    /**
     * Legacy trio, part 1: communication routing on older stacks requires
     * speakerphone to be off before SCO is requested. Without it some ROMs
     * keep the output on the speaker path and never start the SCO stream.
     */
    @Suppress("DEPRECATION")
    private fun silenceSpeakerphone() {
        if (savedSpeakerphoneOn != null) return
        savedSpeakerphoneOn = try {
            audioManager.isSpeakerphoneOn
        } catch (_: Throwable) {
            false
        }
        try {
            audioManager.isSpeakerphoneOn = false
            Log.i(TAG, "Speakerphone off for comm route (was $savedSpeakerphoneOn)")
        } catch (t: Throwable) {
            Log.w(TAG, "setSpeakerphoneOn(false) failed (continuing)", t)
        }
    }

    @Suppress("DEPRECATION")
    private fun restoreSpeakerphone() {
        val saved = savedSpeakerphoneOn ?: return
        savedSpeakerphoneOn = null
        try {
            audioManager.isSpeakerphoneOn = saved
        } catch (t: Throwable) {
            Log.w(TAG, "Speakerphone restore failed (continuing)", t)
        }
    }

    /**
     * Legacy trio, part 2: request SCO through the deprecated API as well as
     * setCommunicationDevice(). It makes this app the SCO client and forces
     * the communication strategy to the headset — the path older VoIP apps
     * use, and the only one observed to start the SCO audio stream on this
     * ROM class.
     */
    @Suppress("DEPRECATION")
    private fun requestLegacySco(reason: String) {
        try {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            Log.i(TAG, "Legacy SCO requested ($reason)")
        } catch (t: Throwable) {
            Log.w(TAG, "Legacy SCO request failed ($reason)", t)
        }
    }

    @Suppress("DEPRECATION")
    private fun stopLegacySco() {
        try {
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
        } catch (t: Throwable) {
            Log.w(TAG, "Legacy SCO stop failed (continuing)", t)
        }
    }

    /**
     * Wait until the framework reports SCO audio up (state broadcast CONNECTED
     * or the platform's own isBluetoothScoOn flag) **and** the SCO input
     * device exists. Device presence alone is not proof on Android 12+: the
     * SCO device is enumerated when HFP connects, and a voice track created in
     * that window initializes fine but is never consumed by the HAL.
     */
    private suspend fun waitForScoConnection(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isScoConnected() && scoInputDevice() != null) return true
            delay(100)
        }
        Log.w(
            TAG,
            "SCO still not up after ${timeoutMs}ms " +
                "(state=${scoStateName(scoAudioState)}, seen=$scoStateSeen, " +
                "scoOn=${isBluetoothScoOn()}, input=${scoInputDevice() != null})",
        )
        return isScoConnected() && scoInputDevice() != null
    }

    @Suppress("DEPRECATION")
    private fun registerScoReceiver() {
        if (scoReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val state = intent.getIntExtra(
                    AudioManager.EXTRA_SCO_AUDIO_STATE,
                    AudioManager.SCO_AUDIO_STATE_ERROR,
                )
                scoAudioState = state
                scoStateSeen = true
                Log.i(TAG, "SCO audio state: ${scoStateName(state)}")
            }
        }
        scoReceiver = receiver
        // The broadcast is sticky: the returned intent carries the state as of
        // registration (e.g. a SCO link already up before we started watching)
        val sticky = ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        sticky?.let {
            val state = it.getIntExtra(
                AudioManager.EXTRA_SCO_AUDIO_STATE,
                AudioManager.SCO_AUDIO_STATE_ERROR,
            )
            scoAudioState = state
            scoStateSeen = true
            Log.i(TAG, "SCO audio state (sticky): ${scoStateName(state)}")
        }
    }

    private fun unregisterScoReceiver() {
        scoReceiver?.let {
            try {
                context.unregisterReceiver(it)
            } catch (t: Throwable) {
                Log.w(TAG, "SCO receiver unregister failed (continuing)", t)
            }
        }
        scoReceiver = null
    }

    /**
     * Platform truth for "SCO audio is up" — not merely "a SCO device
     * exists". `isBluetoothScoOn` alone is not proof either: the framework
     * sets it when the SCO *start request* is accepted, before the link is
     * reported connected. It is only used as a fallback on ROMs that never
     * report any SCO state at all.
     */
    fun isScoConnected(): Boolean =
        scoAudioState == AudioManager.SCO_AUDIO_STATE_CONNECTED ||
            (!scoStateSeen && isBluetoothScoOn())

    /** Compact SCO state for the 30 s diagnostics. */
    fun scoStateLabel(): String = buildString {
        append(if (scoStateSeen) scoStateName(scoAudioState) else "unreported")
        if (isBluetoothScoOn()) append("|on")
    }

    @Suppress("DEPRECATION")
    private fun isBluetoothScoOn(): Boolean = try {
        audioManager.isBluetoothScoOn
    } catch (_: Throwable) {
        false
    }

    private fun scoStateName(state: Int): String = when (state) {
        AudioManager.SCO_AUDIO_STATE_CONNECTED -> "CONNECTED"
        AudioManager.SCO_AUDIO_STATE_CONNECTING -> "CONNECTING"
        AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> "DISCONNECTED"
        AudioManager.SCO_AUDIO_STATE_ERROR -> "ERROR"
        else -> "state=$state"
    }

    private fun hasBluetoothConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED

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

        /** Settle window after media playback stops, before mode/SCO are applied. */
        private const val COMM_PREPARE_SETTLE_MS = 300L

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
