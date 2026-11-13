package dev.tsdroid.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import dev.tsdroid.data.AppLog as Log
import androidx.core.content.ContextCompat
import dev.tslib.AudioConfig
import dev.tslib.EchoCanceller
import dev.tslib.OpusCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

class AudioBridge(
    private val context: Context,
    private val tsClient: TsClient,
) {
    companion object {
        private const val TAG = "AudioBridge"
        const val SAMPLE_RATE = 48000
        const val CODEC_OPUS_VOICE = 4
        const val CODEC_OPUS_MUSIC = 5

        /** Empty reads (read()==0) in a row before the capture is rebuilt (~1s). */
        private const val EMPTY_READ_STREAK_LIMIT = 50

        /**
         * All-zero frames in a row before sustained-silence diagnostics
         * escalate (3s). This no longer triggers a rebuild: healthy mics
         * never read exact zeros (noise floor), but platform NS hard-gating
         * and dead HALs both do, and they are indistinguishable here — the
         * service watchdog arbitrates instead (see [lastCaptureProgressAt]).
         */
        private const val ZERO_FRAME_STREAK_LIMIT = 150

        /**
         * Mic-boost stage with a soft knee: linear below half scale, smoothly
         * compressed above — a boosted capture signal tops out near full
         * scale instead of hard-clipping. Slope-continuous at the knee (k=2
         * keeps the derivative at 1). The playback mix does NOT use this:
         * summing voices is handled by [PcmMixer]'s frame peak limiter, which
         * never distorts the waveform.
         */
        internal fun softClip(x: Float): Short {
            val t = x / 32767f
            val limited = when {
                t > 0.5f -> 0.5f + 0.5f * (1f - 1f / (1f + (t - 0.5f) * 2f))
                t < -0.5f -> -(0.5f + 0.5f * (1f - 1f / (1f + (-t - 0.5f) * 2f)))
                else -> t
            }
            return (limited * 32767f).toInt().toShort()
        }
        private const val FRAME_SIZE_MS = 20
        private const val FRAME_SIZE_SAMPLES = SAMPLE_RATE * FRAME_SIZE_MS / 1000 // 960
        private const val FRAME_SIZE_BYTES = FRAME_SIZE_SAMPLES * 2 // 16-bit PCM = 2 bytes/sample

        // Backstop cap — the write-side watermark bounds latency first; this
        // only kicks in for arrival bursts (e.g. VAD pre-roll dumps)
        private const val MAX_QUEUE_FRAMES = 6

        /** AudioTrack buffer capacity: burst headroom, NOT a latency target. */
        private const val TRACK_BUFFER_FRAMES = 8 // 160ms capacity

        /** Write-side watermark: stop draining while the track holds this much. */
        private const val TARGET_TRACK_BUFFER_MS = 60L

        /** Start draining a fresh talkspurt at this much queued depth… */
        private const val PRIME_MIN_FRAMES = 2

        /** …or once its oldest frame has waited this long, whichever first. */
        private const val PRIME_MAX_WAIT_MS = 60L

        /** PLC frames injected while a talkspurt tail starves, before silence. */
        private const val MAX_HUNGER_PLC_FRAMES = 3

        /** A user counts as "still talking" for hunger-PLC this long after audio. */
        private const val HUNGER_WINDOW_MS = 500L

        /** Receive/encode diagnostics aggregation period. */
        private const val DIAG_FLUSH_MS = 30_000L

        /** Backoff before the restart wrapper relaunches the playback loop. */
        private const val PLAYBACK_RESTART_DELAY_MS = 500L

        /**
         * Consecutive stalled frames (write failure, or a track that is no
         * longer PLAYING while we keep writing) before the output track is
         * rebuilt — 5 frames = 100 ms.
         */
        private const val PLAYBACK_REBUILD_STREAK = 5

        /** Minimum spacing between automatic output-track rebuilds. */
        private const val PLAYBACK_REBUILD_MIN_INTERVAL_MS = 2_000L

        /**
         * How often the capture loop polls the AEC blow-up-guard counter.
         * Well under the 30 s diagnostics window, so the reported delta
         * covers the whole window at a few seconds' resolution.
         */
        private const val AEC_ZEROED_POLL_MS = 5_000L

        /** Output volume multiplier while another app holds transient focus (can-duck). */
        private const val DUCK_FACTOR = 0.3f

        /** Mic frames within this long after a playback write still count as far-active for the ERLE probe. */
        private const val FAR_ACTIVE_GRACE_MS = 100L

        /** Software AEC filter tail — echo paths slower than this cannot be cancelled. */
        private const val AEC_TAIL_MS = 250
        private const val AEC_TAIL_SAMPLES = SAMPLE_RATE * AEC_TAIL_MS / 1000

        /**
         * TS clients emit tiny (~3-byte) comfort-noise frames during open-mic
         * silence — RFC 6716's minimum meaningful payload is far larger, so
         * anything this small is a silence marker, not audio.
         */
        private const val COMFORT_NOISE_FRAME_MAX_BYTES = 3
    }

    private val audioConfig = AudioConfig()

    // Encoder for capture (our mic)
    private var encoder: OpusCodec? = null

    // Per-user decoders and raw opus queues for playback mixing
    private val userDecoders = ConcurrentHashMap<Int, OpusCodec>()
    private val userQueues = ConcurrentHashMap<Int, FrameQueue>()

    /**
     * Users whose decoder could not be created this session (Opus handle
     * exhaustion, OOM). One speaker's failure must not take everyone's audio
     * down with it, so the playback loop skips them instead of throwing.
     */
    private val failedDecoders = ConcurrentHashMap.newKeySet<Int>()

    // Receive-concealment bookkeeping, keyed by user
    private val userLastAudioAt = ConcurrentHashMap<Int, Long>()
    private val userHungerStreak = ConcurrentHashMap<Int, Int>()

    /** Per-user receive diagnostics, aggregated into AppLog every [DIAG_FLUSH_MS]. */
    private class UserDiag {
        var recv = 0
        var lost = 0 // frames inferred missing from sequence gaps
        var fecRepaired = 0
        var plc = 0
        var queuePeak = 0
    }

    private val userDiag = ConcurrentHashMap<Int, UserDiag>()
    @Volatile
    private var lastDiagFlush = SystemClock.elapsedRealtime()

    /** Short-frame sightings this diagnostics window (decode-skip decision pending the count). */
    @Volatile
    private var comfortNoiseFrames = 0

    /**
     * TOC frame-duration histogram this window, one slot per 2.5 ms
     * ([OpusFrames.bucketOf]). A fixed atomic counter array instead of a
     * map: this is incremented once per received packet, and a
     * `HashMap<Long, Int>.merge` boxes a key and a value per frame.
     */
    private val tocDurationCounts = java.util.concurrent.atomic.AtomicIntegerArray(OpusFrames.BUCKETS)
    @Volatile
    private var tocUnparseableFrames = 0

    // Encoder timing ring (µs per encode call) — validates the complexity-8
    // CPU budget on real devices (frame budget is 20ms = 20000µs)
    private val encodeSamplesUs = LongArray(512)
    private var encodeSampleIdx = 0
    @Volatile
    private var encodeSampleCount = 0

    /** Playback-side diagnostics, updated and flushed on the playback thread. */
    private var trackBufPeakMs = 0L
    private val queueDelaySamplesUs = LongArray(512)
    private var queueDelaySampleIdx = 0
    private var queueDelaySampleCount = 0

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    /**
     * ERLE yardstick: quantifies whether the platform AEC actually cancels.
     * Media-routed playback (USAGE_GAME) gives the HAL AEC no reference, so
     * this is expected to read ~0dB — the phase-2 software AEC is accepted
     * against this baseline.
     */
    private val echoProbe = EchoProbe()

    /** Wall clock until which playback counts as far-active for [echoProbe]. */
    @Volatile
    private var farActiveUntil = 0L

    /**
     * Playback→capture reference stream for the software AEC. Fed by the
     * playback loop at the AudioTrack write point, consumed by the capture
     * loop once per frame.
     */
    private val echoReference = EchoReference()

    /** True while a capture session owns a live [EchoCanceller] (gates the reference feed). */
    @Volatile
    private var aecRunning = false

    // Software-AEC processing timing ring (µs per frame), same pattern as
    // the encoder ring — validates the 2ms/frame budget on real devices
    private val echoProcSamplesUs = LongArray(512)
    private var echoProcSampleIdx = 0
    @Volatile
    private var echoProcSampleCount = 0

    // Transmit-path failure counters. The capture loop used to swallow
    // encode/send exceptions silently — a periodic native send failure made
    // frames vanish with no trace in any log. Written on the capture
    // thread, diffed/reset by the playback-thread diagnostics flush.
    @Volatile
    private var encFailCount = 0
    @Volatile
    private var sendFailCount = 0

    /**
     * Zeroed frames the AEC blow-up guard produced in this diagnostics
     * window. The engine's cumulative counter is readable only from the
     * thread that owns the handle: it is single-threaded, and the handle can
     * be destroyed under a diagnostics reader (the capture session ends in
     * its own coroutine), so the capture loop polls the counter and the 30 s
     * flush drains this accumulator — a pure counter read across threads.
     */
    @Volatile
    private var aecZeroedThisWindow = 0L

    /** False once the loaded native library reports no such counter (mixed version). */
    @Volatile
    private var aecZeroedSupported = true

    // Playback discard counters by reason — written on the native pump
    // thread, diffed/reset in the 30s flush. A non-zero suspended count means
    // focus state is dropping the whole session's output.
    @Volatile
    private var discardMutedUser = 0
    @Volatile
    private var discardOutputMuted = 0
    @Volatile
    private var discardSuspended = 0
    @Volatile
    private var discardGainZero = 0

    /** Short/failed AudioTrack writes + total frames written (playback diag). */
    @Volatile
    private var writeFailCount = 0

    /** Output-track rebuilds this diagnostics window (self-heal / route change). */
    @Volatile
    private var trackRebuildCount = 0
    @Volatile
    private var playbackWritesTotal = 0L

    /**
     * Guards [audioRecord] swaps between startCapture/stopCapture (caller
     * thread) and the capture loop's self-heal rebuilds, and the atomic
     * "generation check + session-state write" pairs: startCapture claims the
     * mic under it, endCaptureSession/setCapturingIfCurrent/AEC wiring only
     * touch session-wide state while holding it. Never hold it across native
     * teardown — AudioRecord.stop() can hang and would wedge every restart.
     */
    private val captureSwapLock = Any()

    /** Fixed capture source: unprocessed low-latency input for the software DSP chain. */
    private val captureSource = CaptureSource.VOICE_PERFORMANCE

    /**
     * Monotonic timestamp of the last **non-silent** mic data (a partial
     * read or any full frame with a non-zero sample). The service watchdog
     * compares it against the wall clock to detect a capture loop stuck in a
     * blocking read — or a mic delivering only digital silence: on several
     * ROMs the platform NS hard-gates silence to exact zeros, so all-zero
     * frames deliberately do NOT refresh this — the watchdog arbitrates a
     * restart if silence lasts past its threshold.
     */
    @Volatile
    var lastCaptureProgressAt: Long = 0L
        private set

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * AudioRecord.stop() can hang in native code; teardown must never run
     * inline on the capture coroutine or under captureSwapLock (see [RecorderStopper]).
     */
    private val recorderStopper = RecorderStopper()

    /** Guards [audioTrack] against release while the playback loop reads it. */
    private val trackSwapLock = Any()

    /**
     * Route-change requests, consumed by the thread that owns the resource.
     * The AEC engine is single-threaded (capture loop) and the AudioTrack is
     * swapped under [trackSwapLock] (playback loop), so the device callback
     * only raises flags instead of touching either one.
     */
    @Volatile
    private var aecResetRequested = false
    @Volatile
    private var trackRebuildRequested = false

    private var deviceCallbackRegistered = false

    /**
     * Input/output devices come and go (headset, Bluetooth A2DP, USB): the
     * acoustic path, its delay and the track's route all change with them.
     * Without this the software AEC keeps modelling the old path (audible
     * residual echo until it re-converges) and the output can stay routed to
     * a device that is gone — writes keep succeeding, nothing is heard.
     */
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            onAudioRouteChanged(addedDevices.joinToString { it.type.typeLabel() })
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            onAudioRouteChanged(removedDevices.joinToString { it.type.typeLabel() })
        }
    }

    private fun onAudioRouteChanged(what: String) {
        Log.i(TAG, "Audio devices changed ($what) — resetting AEC and rebuilding the output track")
        aecResetRequested = true
        trackRebuildRequested = true
    }

    private fun Int.typeLabel(): String = when (this) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "builtin-mic"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired-headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired-headphones"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bt-a2dp"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bt-sco"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "usb"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usb-headset"
        else -> "type=$this"
    }

    private var audioFocusRequest: AudioFocusRequest? = null

    // Scope of the running capture — used to retry focus acquisition after
    // a permanent loss (the system never dispatches GAIN for those)
    private var captureScope: CoroutineScope? = null
    private var focusRetryJob: Job? = null

    // Output pause while another app holds audio focus (navigation, calls…)
    @Volatile
    private var outputSuspendedByFocus = false

    // Output volume reduction while another app plays a short prompt (can-duck)
    @Volatile
    private var duckedByFocus = false

    private var captureJob: Job? = null
    private var playbackJob: Job? = null

    /**
     * Capture-session generation, bumped by every startCapture(). The loop's
     * cleanup only touches session-wide state (_isCapturing, the AEC reference
     * feed) while it still owns the current generation: a recorder whose
     * blocking read returns after a watchdog stop→start cycle must not stop
     * the replacement session's mic or unhook its AEC.
     */
    private val captureGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    // Dedicated single-thread scope for audio playback. The exception handler
    // is the last line of defence: the restart wrapper catches everything the
    // loop throws, but an unexpected failure of the wrapper itself must be
    // logged rather than reaching the process' default uncaught handler.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val playbackScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1) +
            kotlinx.coroutines.CoroutineExceptionHandler { _, e ->
                Log.e(TAG, "Playback scope failed", e)
            }
    )

    // Coalescing wake signal for the playback loop: capacity 1 — producers
    // offer() after enqueueing a frame, the loop blocks on poll(20ms) when
    // idle instead of sleeping a fixed tick (instant start, zero idle wakeups)
    private val playbackWake = java.util.concurrent.ArrayBlockingQueue<Unit>(1)

    @Volatile
    var gainFactor: Float = 1.0f

    /**
     * Manual mic gain multiplier applied to captured samples before VAD and
     * encoding. 1.0 = unity; 0 = mic off (the capture loop skips VAD and
     * transmission entirely); values above 1 are soft-clipped against
     * full scale so a loud room cannot hard-clip the boosted signal.
     */
    @Volatile
    var micGainFactor: Float = 1.0f

    @Volatile
    var micMode: MicMode = MicMode.PTT
        private set

    @Volatile
    var vadThresholdDb: Float = VadGate.DEFAULT_THRESHOLD_DB
        private set

    private val vadGate = VadGate()

    fun setMicMode(mode: MicMode) {
        micMode = mode
        vadGate.reset()
    }

    fun setVadThresholdDb(db: Float) {
        vadThresholdDb = db
    }

    @Volatile
    private var mutedUserIds: Set<Int> = emptySet()

    // Per-user playback gain as a linear multiplier (keyed by client id,
    // session-scoped; 1.0 = unity)
    private val userGainFactors = ConcurrentHashMap<Int, Float>()

    fun setUserGainFactor(userId: Int, factor: Float) {
        if (factor == 1f) userGainFactors.remove(userId) else userGainFactors[userId] = factor
    }

    /**
     * Drop queues, decoders and gains for clients that are no longer on the
     * server, so recycled client ids never inherit old state.
     */
    fun pruneUsers(activeIds: Set<Int>) {
        for (id in userDecoders.keys) {
            if (id !in activeIds) {
                userDecoders.remove(id)?.let { decoder ->
                    try { decoder.close() } catch (_: Exception) {}
                }
            }
        }
        userQueues.keys.retainAll(activeIds)
        userGainFactors.keys.retainAll(activeIds)
        userDiag.keys.retainAll(activeIds)
        userLastAudioAt.keys.retainAll(activeIds)
        userHungerStreak.keys.retainAll(activeIds)
        // A recycled client id gets a fresh chance at a decoder
        failedDecoders.retainAll(activeIds)
    }

    private val _isMuted = MutableStateFlow(true) // Start muted (PTT default)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isOutputMuted = MutableStateFlow(false)
    val isOutputMuted: StateFlow<Boolean> = _isOutputMuted.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _isLocalVoiceActive = MutableStateFlow(false)
    val isLocalVoiceActive: StateFlow<Boolean> = _isLocalVoiceActive.asStateFlow()

    fun initialize() {
        try {
            // Explicitly release any old audio stream resources if lingering
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null

            encoder = OpusCodec(audioConfig)
            initAudioTrack()
            startPlaybackLoop()
            // Route tracking: headset/Bluetooth changes must re-converge the
            // AEC and rebuild the track (see audioDeviceCallback)
            if (!deviceCallbackRegistered) {
                try {
                    audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
                    deviceCallbackRegistered = true
                } catch (t: Throwable) {
                    Log.w(TAG, "Audio device callback registration failed; route changes will not reset AEC", t)
                }
            }
            // Push-mode receive: frames are invoked from the native pump the
            // moment they arrive, skipping the event flow entirely
            tsClient.setAudioSink(audioSink)
            Log.i(
                TAG,
                "Audio path init (media): track=${audioTrack != null} " +
                    "mediaVol=${audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)}/" +
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            )
        } catch (e: Exception) {
            Log.e(TAG, "Audio init failed — playback stays down until re-join", e)
            // We don't throw here to prevent JE_AppCustomException
        }
    }

    /** Native-side callback — queues the frame and wakes the playback loop. */
    private val audioSink = object : dev.tslib.AudioSink {
        override fun onAudioFrame(userId: Int, seq: Int, data: ByteArray, isWhisper: Boolean) {
            playAudio(userId, seq, data)
        }
    }

    /**
     * Keeps the service-visible capture flag and the native event-loop poll
     * budget in sync: while capturing, the loop must not block long enough to
     * queue audio frames behind it.
     */
    private fun setCapturing(active: Boolean) {
        _isCapturing.value = active
        tsClient.setCaptureActive(active)
    }

    /**
     * [setCapturing] restricted to the capture session that still owns the
     * mic. A recorder whose blocking read returns after a watchdog
     * stop→start cycle must not clear the flag the replacement session set.
     * The check-and-act runs under [captureSwapLock] so it cannot interleave
     * with a new session's "bump the generation and claim the mic".
     */
    private fun setCapturingIfCurrent(generation: Int, active: Boolean) {
        synchronized(captureSwapLock) {
            if (captureGeneration.get() == generation) setCapturing(active)
        }
    }

    /**
     * Tear down one capture session. Runs in a `finally`, so cancellation and
     * exceptions release this session's AEC engine too — and compares the
     * generation before touching session-wide state: when this loop already
     * lost the mic to a newer startCapture (its read returned late after a
     * watchdog restart), the new session owns `_isCapturing`, the reference
     * feed and the echo canceller, and only this session's own engine and
     * recorder are released here.
     *
     * The comparison and the writes happen under [captureSwapLock] — the same
     * lock [startCapture] takes to bump the generation — so this session
     * cannot read "still current", get preempted, and then clear the state of
     * the session that started in between. The native teardown below stays
     * outside the lock: AudioRecord.stop() can hang (see [RecorderStopper])
     * and must never hold a lock the capture-restart path needs.
     */
    private fun endCaptureSession(generation: Int, aec: EchoCanceller?, record: AudioRecord?) {
        synchronized(captureSwapLock) {
            if (captureGeneration.get() == generation) {
                setCapturing(false)
                _isLocalVoiceActive.value = false
                // Stop the reference feed before tearing the engine down; both
                // are synchronized, so a concurrent playback push can never touch
                // a destroyed native handle.
                aecRunning = false
                echoReference.reset()
            }
        }
        try {
            aec?.close()
        } catch (_: Throwable) {}
        // Release only the local handle. The audioRecord field belongs to
        // startCapture/stopCapture on the caller thread, so this late cleanup
        // can never clear or double-release a replacement instance from a
        // quick stop→start cycle.
        record?.let { recorderStopper.stopAndRelease(it) }
    }

    @SuppressLint("MissingPermission")
    fun startCapture(scope: CoroutineScope) {
        if (_isCapturing.value) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Cannot start capture: RECORD_AUDIO permission is missing")
            return
        }

        val record = createAudioRecord() ?: return
        // Bump the generation and publish the new session as one atomic step
        // with respect to a stale session's cleanup (both take
        // captureSwapLock): the stale loop can no longer read "still current",
        // be preempted, and then clear the flags set right here. The bump
        // stays after createAudioRecord() on purpose — if the recorder cannot
        // be created we return without bumping, so the old session's cleanup
        // still owns and clears the AEC wiring.
        val generation = synchronized(captureSwapLock) {
            val g = captureGeneration.incrementAndGet()
            audioRecord = record
            setCapturing(true)
            g
        }
        captureScope = scope
        lastCaptureProgressAt = SystemClock.elapsedRealtime()
        requestAudioFocus()

        captureJob = scope.launch(Dispatchers.IO) {
            // Handles owned by this session only; the finally below releases
            // them on every exit path — normal end, cancellation, exception.
            var ownedRecord: AudioRecord? = null
            var aec: EchoCanceller? = null
            try {
                val buffer = ShortArray(FRAME_SIZE_SAMPLES)
                val codec = encoder ?: run {
                    Log.e(TAG, "Cannot start capture: Opus encoder is not initialized")
                    setCapturingIfCurrent(generation, false)
                    return@launch
                }
                var record = audioRecord ?: run {
                    Log.e(TAG, "Cannot start capture: AudioRecord missing")
                    setCapturingIfCurrent(generation, false)
                    return@launch
                }
                ownedRecord = record
                // Self-heal bookkeeping. The capture device can die under our
                // feet — aggressive ROMs invalidate VOICE_COMMUNICATION records
                // in the background, device switches leave stale handles. Read
                // errors and repeated empty reads are hard-death fingerprints:
                // rebuild immediately. All-zero PCM is deliberately NOT rebuilt
                // on — healthy mics never read exact zeros, but platform NS
                // hard-gating produces them for minutes on some ROMs, so the two
                // are indistinguishable here and rebuilding just churned every
                // few seconds; sustained digital silence withholds capture
                // progress instead and the service watchdog arbitrates.
                var zeroFrameStreak = 0
                var emptyReadStreak = 0
                var consecutiveRebuilds = 0

                // Software AEC: one engine per capture session, owned by this
                // coroutine and destroyed in the cleanup below. Creation failure
                // (old .so without the JNI symbols, OOM) never blocks capture —
                // the session runs without AEC and the next startCapture retries.
                aec = try {
                    val engine = EchoCanceller(SAMPLE_RATE, FRAME_SIZE_SAMPLES, AEC_TAIL_SAMPLES)
                    // Wire the reference feed only while this session still
                    // owns the mic. A start that was delayed past a newer
                    // session must not steal its wiring: this session's own
                    // cleanup skips these fields (stale generation), so the
                    // handle would dangle.
                    val owned = synchronized(captureSwapLock) {
                        if (captureGeneration.get() != generation) {
                            false
                        } else {
                            echoReference.reset()
                            aecRunning = true
                            aecZeroedSupported = true
                            true
                        }
                    }
                    if (owned) {
                        Log.i(TAG, "Software AEC started (tail=${AEC_TAIL_MS}ms)")
                        engine
                    } else {
                        Log.w(TAG, "Software AEC discarded: capture session was replaced during start")
                        try {
                            engine.close()
                        } catch (_: Throwable) {}
                        null
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Software AEC unavailable this session", t)
                    // This session owns the generation but has no engine. The
                    // previous session's cleanup deliberately skipped these
                    // fields (stale generation), so they can still say
                    // "AEC running": drop the wiring here or the playback loop
                    // keeps feeding a reference nobody consumes and the 30 s
                    // diagnostic reports a stale zeroed-frame count.
                    synchronized(captureSwapLock) {
                        if (captureGeneration.get() == generation) {
                            aecRunning = false
                        }
                    }
                    null
                }
                val aecFar = ShortArray(FRAME_SIZE_SAMPLES)
                val aecOut = ShortArray(FRAME_SIZE_SAMPLES)
                var aecFailStreak = 0
                // AEC blow-up-guard polling (capture-thread owned, see the
                // accumulator's doc: the handle must not be read elsewhere)
                var lastZeroedTotal = -1L
                var lastZeroedPollAt = 0L

                // Rebuild the capture device from inside the loop. Returns the
                // fresh record, or null when capture was stopped meanwhile or
                // the rebuild keeps failing (then the loop ends and the service
                // watchdog restarts capture later).
                suspend fun rebuildOrStop(dead: AudioRecord): AudioRecord? {
                    // Release the dead handle first: stopCapture may run during
                    // the backoff below, and it must find a consistent state.
                    // Off-thread with a bounded wait — an inline native stop() hang
                    // would wedge this rebuild path for good.
                    recorderStopper.stopAndRelease(dead)
                    synchronized(captureSwapLock) {
                        if (audioRecord === dead) audioRecord = null
                    }
                    delay(rebuildBackoffMs(consecutiveRebuilds))
                    if (captureGeneration.get() != generation || !_isCapturing.value || !isActive) return null
                    consecutiveRebuilds++
                    val fresh = createAudioRecord() ?: run {
                        Log.e(TAG, "Capture rebuild failed (attempt $consecutiveRebuilds); ending capture loop")
                        setCapturingIfCurrent(generation, false)
                        return null
                    }
                    synchronized(captureSwapLock) {
                        // Capture stopped, or a newer session owns the mic by
                        // now: don't orphan a live recorder behind its back
                        // (a stale session must never overwrite the field the
                        // new session just published)
                        if (!_isCapturing.value || captureGeneration.get() != generation) {
                            fresh.release()
                            return null
                        }
                        audioRecord = fresh
                    }
                    zeroFrameStreak = 0
                    emptyReadStreak = 0
                    lastCaptureProgressAt = SystemClock.elapsedRealtime()
                    Log.i(TAG, "Capture rebuilt (attempt $consecutiveRebuilds)")
                    return fresh
                }

                // Adopt a rebuilt recorder as the one this session owns.
                // Returns false when the loop must end (capture stopped, or
                // the rebuild kept failing) — rebuildOrStop has already
                // released the dead handle in that case, so ownership is
                // dropped by the caller instead of being released twice.
                suspend fun adoptRebuild(dead: AudioRecord): Boolean {
                    val fresh = rebuildOrStop(dead) ?: return false
                    record = fresh
                    ownedRecord = fresh
                    return true
                }

                // Samples already sitting in `buffer` from a short read. A
                // read that returns fewer than a full frame is a device
                // hiccup, not a reason to throw the samples away: the frame is
                // topped up on the next read (and the VAD state survives, so
                // the onset it was about to transmit is not lost).
                var carry = 0

                while (isActive && _isCapturing.value) {
                    val wanted = FRAME_SIZE_SAMPLES - carry
                    val read = try {
                        record.read(buffer, carry, wanted)
                    } catch (e: Throwable) {
                        Log.e(TAG, "Microphone read failed; attempting capture rebuild", e)
                        if (!adoptRebuild(record)) {
                            ownedRecord = null
                            break
                        }
                        carry = 0
                        continue
                    }
                    if (read < 0) {
                        Log.w(TAG, "Microphone read error $read; attempting capture rebuild")
                        if (!adoptRebuild(record)) {
                            ownedRecord = null
                            break
                        }
                        carry = 0
                        continue
                    }
                    if (read == 0) {
                        emptyReadStreak++
                        if (emptyReadStreak >= EMPTY_READ_STREAK_LIMIT) {
                            Log.w(TAG, "Microphone returned $emptyReadStreak empty reads; attempting capture rebuild")
                            if (!adoptRebuild(record)) {
                                ownedRecord = null
                                break
                            }
                            carry = 0
                        } else if (emptyReadStreak == EMPTY_READ_STREAK_LIMIT / 2) {
                            // Early warning: short sessions die before the 30s flush sees anything
                            Log.w(TAG, "Microphone empty reads at $emptyReadStreak (half of rebuild limit)")
                        }
                        continue
                    }
                    emptyReadStreak = 0
                    if (read < wanted) {
                        // Partial read: this sample run still proves the
                        // device is alive, but the frame is not complete yet
                        carry += read
                        lastCaptureProgressAt = SystemClock.elapsedRealtime()
                        continue
                    }
                    carry = 0
                    var frameSilent = true
                    for (s in buffer) {
                        if (s.toInt() != 0) {
                            frameSilent = false
                            break
                        }
                    }
                    if (frameSilent) {
                        // Digital silence: a healthy mic never reads exact
                        // zeros (noise floor), but NS-gated silence and a
                        // dead HAL both do — indistinguishable here, so no
                        // rebuild. Progress is withheld (below) and the
                        // service watchdog arbitrates a restart if it lasts.
                        zeroFrameStreak++
                        when (zeroFrameStreak) {
                            ZERO_FRAME_STREAK_LIMIT / 2 -> Log.i(
                                TAG,
                                "Digital silence building ($zeroFrameStreak frames) — platform NS gate? no rebuild",
                            )
                            ZERO_FRAME_STREAK_LIMIT -> Log.w(
                                TAG,
                                "Digital silence sustained ($zeroFrameStreak frames); not rebuilding — watchdog arbitrates if it persists",
                            )
                        }
                    } else {
                        zeroFrameStreak = 0
                        consecutiveRebuilds = 0
                        // Only non-silent data counts as capture progress:
                        // NS-gated zeros (or a dead mic) must not keep the
                        // watchdog satisfied forever
                        lastCaptureProgressAt = SystemClock.elapsedRealtime()
                    }
                    // Software AEC: every full frame on the raw signal, before
                    // micGain (the filter converges best on raw samples) and
                    // before the send gate (mute only gates the encoder and
                    // must not starve the adaptive filter).
                    val engine = aec
                    if (engine != null) {
                        // Route change: the learned path and the reference
                        // timeline are both stale, and the engine belongs to
                        // this thread — the device callback only raised a flag
                        if (aecResetRequested) {
                            aecResetRequested = false
                            echoReference.reset()
                            try {
                                engine.reset()
                                aecFailStreak = 0
                                Log.i(TAG, "AEC reset after audio route change")
                            } catch (t: Throwable) {
                                Log.w(TAG, "AEC reset failed", t)
                            }
                        }
                        echoReference.read(aecFar)
                        val t0 = System.nanoTime()
                        try {
                            aec.process(buffer, aecFar, aecOut)
                            System.arraycopy(aecOut, 0, buffer, 0, FRAME_SIZE_SAMPLES)
                            aecFailStreak = 0
                        } catch (t: Throwable) {
                            // Bypass on engine error: forward the raw mic — an
                            // audible echo beats swallowed speech.
                            if (aecFailStreak++ == 0) Log.e(TAG, "AEC frame bypass: ${t.message}")
                        }
                        recordEchoProcUs((System.nanoTime() - t0) / 1000)
                        // Blow-up-guard counter, polled here (never from the
                        // diagnostics thread): a zeroed frame is a 20 ms hole
                        // in the transmitted stream, so any non-zero rate is a
                        // bug report and the 30 s line reports the delta
                        val zeroedNow = SystemClock.elapsedRealtime()
                        if (zeroedNow - lastZeroedPollAt >= AEC_ZEROED_POLL_MS) {
                            lastZeroedPollAt = zeroedNow
                            val total = try {
                                engine.zeroedFrames()
                            } catch (t: Throwable) {
                                -1L
                            }
                            if (total < 0) {
                                // Mixed-version native library — stop asking
                                aecZeroedSupported = false
                            } else {
                                if (lastZeroedTotal in 0 until total) {
                                    aecZeroedThisWindow += total - lastZeroedTotal
                                }
                                lastZeroedTotal = total
                            }
                        }
                    }
                    // ERLE probe observes the post-AEC mic — what actually gets
                    // transmitted; unaffected by mute (which only gates the
                    // encoder).
                    echoProbe.onMicFrame(buffer, SystemClock.elapsedRealtime() < farActiveUntil)
                    if (!_isMuted.value && micGainFactor != 0f) {
                        // Mic gain is applied before VAD and encode so "turn it up
                        // and be heard" behaves uniformly in both mic modes
                        val gain = micGainFactor
                        if (gain != 1f) {
                            for (i in 0 until FRAME_SIZE_SAMPLES) {
                                buffer[i] = softClip(buffer[i] * gain)
                            }
                        }
                        if (micMode == MicMode.VAD) {
                            // Gate transmission on the voice-activation state machine
                            vadGate.openThresholdDb = vadThresholdDb
                            val toSend = vadGate.process(buffer, SystemClock.elapsedRealtime())
                            if (toSend != null) {
                                for (frame in toSend) {
                                    val encoded = try {
                                        val t0 = System.nanoTime()
                                        val out = codec.encode(shortsToBytes(frame))
                                        recordEncodeUs((System.nanoTime() - t0) / 1000)
                                        out
                                    } catch (e: Exception) {
                                        encFailCount++
                                        Log.w(TAG, "Opus encode failed (frame dropped)", e)
                                        null
                                    }
                                    if (encoded != null) {
                                        try {
                                            tsClient.sendAudio(encoded, CODEC_OPUS_VOICE)
                                        } catch (e: Exception) {
                                            sendFailCount++
                                            Log.w(TAG, "sendAudio failed (frame dropped)", e)
                                        }
                                    }
                                }
                            }
                            _isLocalVoiceActive.value = vadGate.isActive
                        } else {
                            var energy = 0L
                            for (i in 0 until FRAME_SIZE_SAMPLES) {
                                energy += buffer[i].toLong() * buffer[i].toLong()
                            }
                            val rms = Math.sqrt(energy.toDouble() / FRAME_SIZE_SAMPLES)
                            val isVoiceActive = rms > 150.0 // Adjusted threshold for voice activity
                            _isLocalVoiceActive.value = isVoiceActive

                            val pcmBytes = shortsToBytes(buffer)
                            val encoded = try {
                                val t0 = System.nanoTime()
                                val out = codec.encode(pcmBytes)
                                recordEncodeUs((System.nanoTime() - t0) / 1000)
                                out
                            } catch (e: Exception) {
                                encFailCount++
                                Log.w(TAG, "Opus encode failed (frame dropped)", e)
                                null
                            }
                            if (encoded != null) {
                                try {
                                    tsClient.sendAudio(encoded, CODEC_OPUS_VOICE)
                                } catch (e: Exception) {
                                    sendFailCount++
                                    Log.w(TAG, "sendAudio failed (frame dropped)", e)
                                }
                            }
                        }
                    } else {
                        vadGate.reset()
                        _isLocalVoiceActive.value = false
                    }
                }
            } finally {
                // Runs on every exit path: normal end, cancellation, or
                // an exception. Session-wide state is touched only while
                // this loop still owns the current generation.
                endCaptureSession(generation, aec, ownedRecord)
            }
        }
    }

    fun stopCapture() {
        setCapturing(false)
        captureJob?.cancel()
        captureJob = null
        focusRetryJob?.cancel()
        focusRetryJob = null
        captureScope = null
        vadGate.reset()
        abandonAudioFocus()
        // Swap the fields out under the lock, but never wait for the record's
        // teardown while holding it: a hanging native stop() on the caller
        // thread would deadlock every later stop/rebuild behind captureSwapLock.
        val record = synchronized(captureSwapLock) {
            val rec = audioRecord
            audioRecord = null
            rec
        }
        if (record != null) {
            recorderStopper.stopAndRelease(record)
        }
    }

    /**
     * Create and start a recorder for the requested capture source, or null
     * on failure. Shared by [startCapture] and the capture loop's self-heal
     * rebuilds.
     */
    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // Requested raw source first, then MIC, then the platform voice
        // source as the last resort: the all-software pipeline wants an
        // unprocessed signal (vendor voice chains nonlinearly process the
        // echo path), and a silent fallback beats a dead capture loop
        val requested = captureSource.resolveSourceId()
        Log.i(TAG, "Capture source: ${captureSource.raw} (id=$requested)")
        return tryCreateAudioRecord(requested, minBuf)
            ?: if (requested != MediaRecorder.AudioSource.MIC) {
                tryCreateAudioRecord(MediaRecorder.AudioSource.MIC, minBuf)
            } else {
                null
            }
            ?: if (requested != MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                tryCreateAudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, minBuf)
            } else {
                null
            }
    }

    private fun tryCreateAudioRecord(
        sourceId: Int,
        minBuf: Int,
    ): AudioRecord? {
        val record = try {
            AudioRecord(
                sourceId,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, FRAME_SIZE_BYTES * 4),
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create AudioRecord (source=$sourceId)", e)
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord is not initialized (source=$sourceId)")
            record.release()
            return null
        }
        return try {
            record.startRecording()
            Log.i(TAG, "Capture started (source=$sourceId)")
            record
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start microphone capture (source=$sourceId)", e)
            record.release()
            null
        }
    }

    /** Wait before the Nth consecutive capture rebuild (2s → 30s cap). */
    private fun rebuildBackoffMs(consecutiveRebuilds: Int): Long = when {
        consecutiveRebuilds <= 0 -> 2_000L
        consecutiveRebuilds == 1 -> 5_000L
        consecutiveRebuilds == 2 -> 15_000L
        else -> 30_000L
    }

    /**
     * Hold audio focus for the duration of a voice session: without it,
     * incoming calls, navigation prompts and other media apps play over
     * (or under) the voice stream with no coordination.
     */
    private fun requestAudioFocus() {
        if (audioFocusRequest != null) return
        val focusAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            Log.i(
                TAG,
                "Audio focus change: $change (ducked=$duckedByFocus suspended=$outputSuspendedByFocus)",
            )
            when (change) {
                // Permanent loss never gets a GAIN callback afterwards —
                // duck the output and keep re-requesting in the background
                // until focus comes back, so a finished call or closed media
                // app restores full volume without a reconnect
                AudioManager.AUDIOFOCUS_LOSS -> {
                    duckedByFocus = true
                    suspendOutput(false)
                    startFocusRetry()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> suspendOutput(true)
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> duckedByFocus = true
                AudioManager.AUDIOFOCUS_GAIN -> {
                    duckedByFocus = false
                    suspendOutput(false)
                    focusRetryJob?.cancel()
                    focusRetryJob = null
                }
            }
        }
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(focusAttributes)
            .setOnAudioFocusChangeListener(listener)
            .build()
        // Keep the request object even when denied: the retry loop re-uses it
        audioFocusRequest = request
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // Denied at start: keep running ducked until the retry wins focus
            Log.w(TAG, "Audio focus not granted; ducking output until focus is available")
            duckedByFocus = true
            startFocusRetry()
        }
    }

    /** Re-request focus with exponential backoff until it is granted again. */
    private fun startFocusRetry() {
        if (focusRetryJob?.isActive == true) return
        val scope = captureScope ?: return
        focusRetryJob = scope.launch {
            var backoffMs = 2_000L
            while (isActive && _isCapturing.value) {
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(15_000L)
                val request = audioFocusRequest ?: break
                if (audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    Log.i(TAG, "Audio focus regained after retry")
                    duckedByFocus = false
                    suspendOutput(false)
                    break
                }
            }
        }
    }

    private fun abandonAudioFocus() {
        focusRetryJob?.cancel()
        focusRetryJob = null
        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
        }
        audioFocusRequest = null
        suspendOutput(false)
        duckedByFocus = false
    }

    private fun suspendOutput(suspend: Boolean) {
        if (outputSuspendedByFocus == suspend) return
        Log.i(TAG, "Output suspended=$suspend (focus)")
        outputSuspendedByFocus = suspend
        if (suspend) {
            // Discard anything queued — a resume after a focus gap must not
            // replay stale audio
            for ((_, queue) in userQueues) {
                queue.clear()
            }
        }
    }

    /**
     * Apply the static per-channel encoder profile. Called on join and on
     * channel switch with the values the server reports for the channel —
     * a pure configuration apply, no runtime adaptation. The encoder keeps
     * these settings until the next call.
     */
    fun applyChannelProfile(quality: Int, codec: Int) {
        val codecRef = encoder ?: return
        if (codec == CODEC_OPUS_MUSIC) {
            Log.i(TAG, "Channel uses Opus Music (codec 5) — music mode not implemented, using voice profile")
        }
        val profile = AudioProfiles.resolve(quality)
        try {
            codecRef.setBitrate(profile.bitrate)
            codecRef.setFec(profile.fecEnabled)
            codecRef.setExpectedPacketLoss(profile.expectedPacketLossPercent)
            Log.i(TAG, "Channel audio profile: quality=$quality codec=$codec bitrate=${profile.bitrate} fec=on(${profile.expectedPacketLossPercent}%)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply channel audio profile", e)
        }
    }

    private fun initAudioTrack() {
        audioTrack = newAudioTrack()
    }

    /**
     * Build and start a fresh output track, or null when the device refuses
     * one. The low-latency performance mode is preferred but not every device
     * accepts it, so a rejection falls back to a plain track instead of
     * leaving the session silent.
     */
    private fun newAudioTrack(): AudioTrack? {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        var fresh = buildAudioTrack(minBuf, lowLatency = true)
        if (fresh?.state != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "Low-latency track not initialized; retrying without performance mode")
            fresh?.release()
            fresh = buildAudioTrack(minBuf, lowLatency = false)
        }
        if (fresh?.state != AudioTrack.STATE_INITIALIZED) {
            fresh?.release()
            return null
        }
        fresh.play()
        return fresh
    }

    /**
     * Swap in a new [AudioTrack] and release the old one. Called from the
     * playback loop when the current track can no longer be played into
     * (device removed, HAL reset, writes failing) or when the route changed.
     * Returns null when no track could be created — the caller keeps the old
     * one and retries on a later frame.
     */
    private fun replaceAudioTrack(reason: String): AudioTrack? {
        val fresh = newAudioTrack() ?: run {
            Log.e(TAG, "Output track rebuild failed ($reason); retrying on a later frame")
            return null
        }
        val old = synchronized(trackSwapLock) {
            val previous = audioTrack
            audioTrack = fresh
            previous
        }
        if (old != null && old !== fresh) {
            // Out of the lock, and each call guarded: a device that already
            // went away can throw here
            try {
                old.pause()
            } catch (_: Throwable) {}
            try {
                old.flush()
            } catch (_: Throwable) {}
            try {
                old.release()
            } catch (_: Throwable) {}
        }
        trackRebuildCount++
        Log.i(TAG, "Output track rebuilt ($reason)")
        return fresh
    }

    private fun buildAudioTrack(minBuf: Int, lowLatency: Boolean): AudioTrack {
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, FRAME_SIZE_BYTES * TRACK_BUFFER_FRAMES))
        if (lowLatency) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        return builder.build()
    }

    /**
     * Starts the (self-restarting) playback loop. Runs once, at init.
     */
    private fun startPlaybackLoop() {
        playbackJob = playbackScope.launch { supervisePlayback() }
    }

    /**
     * Restart wrapper around the playback loop: a throw that escapes the
     * per-frame guards would otherwise end playback for the whole session
     * (startPlaybackLoop runs once, at init). One failing frame must not
     * silence everyone until the next connection.
     */
    private suspend fun supervisePlayback() {
        while (currentCoroutineContext().isActive) {
            try {
                runPlaybackLoop()
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "Playback loop crashed; restarting", t)
                delay(PLAYBACK_RESTART_DELAY_MS)
            }
        }
    }

    /**
     * Playback loop: decodes one opus frame per user, conceals detected
     * loss, mixes PCM, writes one combined frame to AudioTrack. All decoder
     * access is on this single thread so no synchronization is needed on
     * decoders.
     *
     * Concealment policy (static, no adaptation): a gap of N≥1 missing
     * frames recovers exactly the most recent one from the FEC data embedded
     * in the surviving frame; anything older has no reference and its slot
     * is skipped. When a talkspurt tail starves, up to
     * [MAX_HUNGER_PLC_FRAMES] PLC frames extend it before going silent.
     */
    private suspend fun runPlaybackLoop() {
        val mixBuffer = ShortArray(FRAME_SIZE_SAMPLES)
        // Integer accumulator: summing voices must not clip per sample, the
        // rendered frame gets one peak-limiter pass instead (see PcmMixer)
        val mixAcc = IntArray(FRAME_SIZE_SAMPLES)
        val limiter = PeakLimiter()
        val decodeBuffer = ShortArray(FRAME_SIZE_SAMPLES)
        // Frames written vs. frames played by the current track. Owns the
        // 32-bit playback-head unwrapping (a signed read goes negative after
        // ~12.4 h of continuous playback and used to silence the output).
        val depth = PlaybackDepth()
        var currentTrack: AudioTrack? = null
        // Consecutive frames the track could not take audio, and the last
        // time an automatic rebuild happened (churn guard)
        var stalledStreak = 0
        var lastRebuildAt = 0L

        /**
         * Replace a track the current one can no longer play through.
         * Everything that measured the old timeline is restarted with it.
         */
        fun rebuildTrack(reason: String) {
            lastRebuildAt = SystemClock.elapsedRealtime()
            stalledStreak = 0
            if (replaceAudioTrack(reason) != null) {
                // The new track has its own head counter and route delay
                currentTrack = null
                depth.reset()
                // The AEC reference timeline belonged to the old route
                echoReference.reset()
            }
        }

        // Sum one decoded frame into the mix accumulator with per-user gain
        fun mixIn(userId: Int, pcm: ByteArray): Boolean {
            return try {
                bytesToShorts(pcm, decodeBuffer)
                PcmMixer.accumulate(mixAcc, decodeBuffer, userGainFactors[userId] ?: 1f)
                true
            } catch (_: Exception) {
                false
            }
        }

        while (currentCoroutineContext().isActive) {
            var hasData = false
            java.util.Arrays.fill(mixAcc, 0)
            val now = SystemClock.elapsedRealtime()

            if (now - lastDiagFlush >= DIAG_FLUSH_MS) {
                lastDiagFlush = now
                flushAudioDiag()
            }

            // A device change republished the route: rebuild before decoding
            // anything into a track that may no longer be routed anywhere
            if (trackRebuildRequested) {
                trackRebuildRequested = false
                rebuildTrack("audio route changed")
            }

            // Write-side latency watermark: AudioTrack drains in real
            // time while this loop writes at decode pace — without a cap,
            // any burst piles up inside the track buffer and lingers as
            // sustained latency. Capacity is headroom, this is the bound.
            var trackNow = synchronized(trackSwapLock) { audioTrack }
            if (trackNow !== currentTrack) {
                currentTrack = trackNow
                depth.reset()
            }
            if (trackNow == null) {
                // Track released — nothing to write into
                playbackWake.poll(10, java.util.concurrent.TimeUnit.MILLISECONDS)
                continue
            }
            // Self-heal: a track that stopped playing (removed device, HAL
            // reset, focus interaction) stays silent forever because writes
            // into it still succeed — only playState reports it
            if (trackNow.playState != AudioTrack.PLAYSTATE_PLAYING) {
                stalledStreak++
                if (stalledStreak >= PLAYBACK_REBUILD_STREAK &&
                    now - lastRebuildAt >= PLAYBACK_REBUILD_MIN_INTERVAL_MS
                ) {
                    rebuildTrack("playState=${trackNow.playState}")
                    trackNow = synchronized(trackSwapLock) { audioTrack } ?: continue
                    currentTrack = trackNow
                    depth.reset()
                }
            } else {
                stalledStreak = 0
            }
            val bufferedMs = depth.bufferedMs(trackNow.playbackHeadPosition)
            if (bufferedMs > trackBufPeakMs) trackBufPeakMs = bufferedMs

            if (bufferedMs > TARGET_TRACK_BUFFER_MS) {
                playbackWake.poll(20, java.util.concurrent.TimeUnit.MILLISECONDS)
                continue
            }

            // Talkspurt-start priming: hold the first frame(s) until a
            // tiny backlog exists (depth 2) so the second frame isn't
            // chased by an underrun — unless the oldest has waited too
            // long already; onset delay is then capped at ~60ms
            var totalDepth = 0
            var oldestAge = Long.MAX_VALUE
            for ((_, queue) in userQueues) {
                val s = queue.size
                if (s > 0) {
                    totalDepth += s
                    val age = queue.oldestAgeMs(now)
                    if (age < oldestAge) oldestAge = age
                }
            }
            if (totalDepth in 1 until PRIME_MIN_FRAMES && oldestAge < PRIME_MAX_WAIT_MS) {
                playbackWake.poll(5, java.util.concurrent.TimeUnit.MILLISECONDS)
                continue
            }

            for ((userId, queue) in userQueues) {
                // A sender whose decoder could not be created is skipped for
                // the rest of the session: retrying every frame would throw
                // once per 20ms and log nothing new
                if (userId in failedDecoders) {
                    queue.clear()
                    continue
                }
                // Decoder creation can throw (native handle exhaustion, OOM).
                // Outside the per-frame try it used to kill the whole playback
                // coroutine — and with it everyone's audio
                val decoder = try {
                    userDecoders.getOrPut(userId) { OpusCodec(audioConfig) }
                } catch (t: Throwable) {
                    failedDecoders.add(userId)
                    Log.e(TAG, "Opus decoder unavailable for user $userId; skipping that sender", t)
                    queue.clear()
                    continue
                }
                val entry = queue.pollFirst()
                if (entry == null) {
                    // Hunger: if this user was talking moments ago, bridge
                    // the tail with PLC for a few frames, then fall silent
                    val lastAudio = userLastAudioAt[userId] ?: continue
                    val streak = userHungerStreak.getOrPut(userId) { 0 }
                    if (streak < MAX_HUNGER_PLC_FRAMES && now - lastAudio < HUNGER_WINDOW_MS) {
                        if (mixIn(userId, decoder.decodePlc())) {
                            hasData = true
                            userHungerStreak[userId] = streak + 1
                            userDiag.getOrPut(userId) { UserDiag() }.plc++
                        }
                    }
                    continue
                }
                userHungerStreak[userId] = 0
                userLastAudioAt[userId] = now
                recordQueueDelayUs((now - entry.enqueuedAt) * 1000)

                try {
                    if (entry.gap > 0) {
                        // Recover the frame right before this one from its
                        // embedded FEC data; earlier gaps stay silent
                        if (mixIn(userId, decoder.decode(entry.frame, true))) {
                            hasData = true
                        }
                    }
                    if (mixIn(userId, decoder.decode(entry.frame))) {
                        hasData = true
                    }
                } catch (_: Exception) {}

                val diag = userDiag.getOrPut(userId) { UserDiag() }
                diag.recv++
                if (entry.gap > 0) {
                    diag.lost += entry.gap
                    diag.fecRepaired++
                }
                if (queue.size > diag.queuePeak) diag.queuePeak = queue.size
            }

            if (hasData) {
                // Focus ducking multiplies the user gain — audio stays
                // faintly audible under another app's short prompt. The mix
                // is rendered through the peak limiter, so a frame whose
                // summed voices exceed full scale is scaled down as a whole
                // instead of being clamped sample by sample.
                val outGain = gainFactor * (if (duckedByFocus) DUCK_FACTOR else 1f)
                val frameGain = outGain * limiter.next(PcmMixer.peak(mixAcc, outGain))
                PcmMixer.render(mixAcc, mixBuffer, frameGain)
                val track = synchronized(trackSwapLock) { audioTrack }
                val bytes = shortsToBytes(mixBuffer)
                val written = try {
                    track?.write(bytes, 0, bytes.size) ?: -1
                } catch (t: IllegalStateException) {
                    // Track released mid-write — drop this frame; the
                    // next iteration re-reads the field
                    Log.w(TAG, "Write hit a released track", t)
                    -1
                }
                if (written != bytes.size) {
                    writeFailCount++
                    if (writeFailCount == 1) {
                        Log.w(TAG, "AudioTrack.write returned $written of ${bytes.size}")
                    }
                    // Repeated failures are the other face of a dead track:
                    // rebuild instead of dropping every frame until reconnect
                    stalledStreak++
                    if (stalledStreak >= PLAYBACK_REBUILD_STREAK &&
                        now - lastRebuildAt >= PLAYBACK_REBUILD_MIN_INTERVAL_MS
                    ) {
                        rebuildTrack("write returned $written of ${bytes.size}")
                        continue
                    }
                } else {
                    stalledStreak = 0
                }
                val framesWritten = bytes.size / 2
                depth.onFramesWritten(framesWritten)
                playbackWritesTotal += framesWritten
                // AEC reference: exactly the PCM handed to the speaker
                // (post-gain, post-duck, post-limiter) — feeding the raw
                // mix pre-DUCK would feed a distorted reference.
                if (aecRunning) {
                    echoReference.push(mixBuffer, SystemClock.elapsedRealtime())
                }
                // Probe sees the post-gain mix — exactly what the speaker plays
                echoProbe.onFarFrame(mixBuffer)
                farActiveUntil = now + FAR_ACTIVE_GRACE_MS
            } else {
                // No audio data — block until a frame arrives (producer
                // offers the wake signal) or the idle safety-net timeout
                // elapses. Speech onset is still instant (offer wakes the
                // loop immediately); the long timeout just keeps silence
                // at ~4 wakeups/s instead of 50.
                playbackWake.poll(250, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
        }
    }

    /**
     * Queue an opus packet for a specific user. Called from any thread;
     * decoding happens on the playback thread.
     */
    fun setMutedUserIds(userIds: Set<Int>) {
        mutedUserIds = userIds
    }

    fun playAudio(userId: Int, seq: Int, opusData: ByteArray) {
        // Comfort-noise marker (TS open-mic silence emits ~3-byte frames):
        // advance the sender's sequence so the next real frame's loss
        // inference stays correct, then stop — no decode, no queue, no PLC,
        // no TOC stats. The speaking indicator comes from server talk-status
        // events, never from decoded audio, so it stays dark by itself.
        if (opusData.size <= COMFORT_NOISE_FRAME_MAX_BYTES) {
            comfortNoiseFrames++
            if (userId in mutedUserIds) return
            if (_isOutputMuted.value) return
            if (outputSuspendedByFocus) return
            if (gainFactor == 0f) return
            userQueues.getOrPut(userId) { FrameQueue(MAX_QUEUE_FRAMES) }.skip(seq)
            return
        }
        // Duration histogram: the pipeline assumes constant 20ms frames; a
        // sender emitting other durations would be silently truncated by the
        // fixed 960-sample decode buffers
        val durationUs = OpusFrames.totalDurationUs(opusData)
        if (durationUs == null) {
            tocUnparseableFrames++
        } else {
            tocDurationCounts.incrementAndGet(OpusFrames.bucketOf(durationUs))
        }
        if (userId in mutedUserIds) { discardMutedUser++; return }
        if (_isOutputMuted.value) { discardOutputMuted++; return } // Global output mute — discard incoming audio
        if (outputSuspendedByFocus) { discardSuspended++; return } // Another app holds focus — discard instead of queueing
        if (gainFactor == 0f) { discardGainZero++; return } // Speaker slider at 0% — same as muted
        val queue = userQueues.getOrPut(userId) { FrameQueue(MAX_QUEUE_FRAMES) }
        // Full queue evicts the OLDEST frame: fresh speech beats stale speech
        queue.offer(opusData, seq, SystemClock.elapsedRealtime())
        playbackWake.offer(Unit)
    }

    fun setMuted(muted: Boolean) {
        _isMuted.value = muted
        tsClient.setInputMuted(muted)
    }

    fun toggleMute() {
        val newState = !_isMuted.value
        _isMuted.value = newState
        tsClient.setInputMuted(newState)
    }

    fun setOutputMuted(muted: Boolean) {
        _isOutputMuted.value = muted
        // When output is muted, clear all queued audio so nothing plays
        if (muted) {
            for ((_, queue) in userQueues) {
                queue.clear()
            }
        }
    }

    fun toggleOutputMute() {
        setOutputMuted(!_isOutputMuted.value)
    }

    fun release() {
        stopCapture()
        if (deviceCallbackRegistered) {
            try {
                audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            } catch (t: Throwable) {
                Log.w(TAG, "Audio device callback unregistration failed", t)
            }
            deviceCallbackRegistered = false
        }
        playbackJob?.cancel()
        playbackJob = null
        playbackScope.cancel()
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        encoder?.close()
        encoder = null
        // Close per-user decoders
        for (decoder in userDecoders.values) {
            try { decoder.close() } catch (_: Exception) {}
        }
        userDecoders.clear()
        userQueues.clear()
        userDiag.clear()
        userLastAudioAt.clear()
        userHungerStreak.clear()
        failedDecoders.clear()
    }

    /** Emit the 30s receive/encode diagnostic aggregates into AppLog. */
    private fun flushAudioDiag() {
        /**
         * Zeroed-frame delta the capture loop accumulated over the window;
         * -1 = unknown (no AEC this session, or a native library without the
         * counter). Drains the accumulator so the next window starts at zero.
         */
        fun readEchoZeroedDelta(): Long {
            if (!aecRunning || !aecZeroedSupported) return -1L
            val delta = aecZeroedThisWindow
            aecZeroedThisWindow = 0
            return delta
        }

        for ((userId, d) in userDiag) {
            if (d.recv > 0 || d.lost > 0 || d.plc > 0) {
                Log.i(TAG, "AudioRx user=$userId 30s: recv=${d.recv} lost=${d.lost} fec=${d.fecRepaired} plc=${d.plc} queuePeak=${d.queuePeak}")
            }
            d.recv = 0
            d.lost = 0
            d.fecRepaired = 0
            d.plc = 0
            d.queuePeak = 0
        }
        val n = encodeSampleCount
        if (n > 0) {
            encodeSampleCount = 0
            val samples = encodeSamplesUs.take(n.coerceAtMost(encodeSamplesUs.size)).sorted()
            val p95 = samples[(samples.size * 95 / 100).coerceIn(0, samples.size - 1)]
            // san0 = blow-up-guard zeroed frames this window (a 20 ms hole
            // each); -1 means no live AEC or a pre-counter native library
            val sanDelta = readEchoZeroedDelta()
            // Real capture route as reported by the framework (-1 unknown):
            // shows which input device the recorder actually landed on
            val recRouteType = try {
                audioRecord?.routedDevice?.type ?: -1
            } catch (_: Throwable) {
                -1
            }
            Log.i(
                TAG,
                "AudioEnc 30s: n=${samples.size} p95=${p95}us max=${samples.last()}us " +
                    "(budget 20000us) micGain=$micGainFactor mode=$micMode vadDb=$vadThresholdDb " +
                    "recRoute=$recRouteType " +
                    "san0=${if (sanDelta < 0) "n/a" else sanDelta} " +
                    "encFail=$encFailCount sendFail=$sendFailCount",
            )
            encFailCount = 0
            sendFailCount = 0
        }
        // Playback path snapshot: a silent session with a healthy head and
        // full buffers means the loss is downstream (volume/focus/routing),
        // not in the mix pipeline — surface every field instead of guessing
        run {
            val outTrack = audioTrack
            // Unsigned on purpose: the raw counter goes negative after
            // ~12.4 h and a negative head in the log hides the wrap
            val head = outTrack?.playbackHeadPosition?.toLong()?.and(0xFFFF_FFFFL) ?: -1L
            val playState = outTrack?.playState ?: -1
            // Actual playback route (not our intent)
            val trackRouteType = try {
                outTrack?.routedDevice?.type ?: -1
            } catch (_: Throwable) {
                -1
            }
            Log.i(
                TAG,
                "AudioOut 30s: trackBufPeak=${trackBufPeakMs}ms (target ${TARGET_TRACK_BUFFER_MS}ms) " +
                    "head=$head writes=$playbackWritesTotal playState=$playState " +
                    "underruns=${outTrack?.underrunCount ?: -1} " +
                    "musicVol=${audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)} " +
                    "mode=${audioManager.mode} " +
                    "suspended=$outputSuspendedByFocus ducked=$duckedByFocus " +
                    "trackRoute=$trackRouteType " +
                    "discard[susp=$discardSuspended outMuted=$discardOutputMuted gain0=$discardGainZero userMuted=$discardMutedUser] " +
                    "writeFail=$writeFailCount trackRebuilds=$trackRebuildCount",
            )
            trackBufPeakMs = 0
            discardSuspended = 0
            discardOutputMuted = 0
            discardGainZero = 0
            discardMutedUser = 0
            writeFailCount = 0
            trackRebuildCount = 0
        }
        val qn = queueDelaySampleCount
        if (qn > 0) {
            queueDelaySampleCount = 0
            val samples = queueDelaySamplesUs.take(qn.coerceAtMost(queueDelaySamplesUs.size)).sorted()
            val p95 = samples[(samples.size * 95 / 100).coerceIn(0, samples.size - 1)]
            Log.i(TAG, "AudioQueue 30s: n=${samples.size} p95=${p95}us (enqueue→play)")
        }
        val cn = comfortNoiseFrames
        if (cn > 0) {
            Log.i(TAG, "AudioComfort 30s: shortFrames=$cn (≤${COMFORT_NOISE_FRAME_MAX_BYTES}B, decode-skip pending)")
            comfortNoiseFrames = 0
        }
        var tocPackets = 0
        var tocHist: StringBuilder? = null
        for (i in 0 until OpusFrames.BUCKETS) {
            val n = tocDurationCounts.getAndSet(i, 0)
            if (n <= 0) continue
            tocPackets += n
            val name = if (i == OpusFrames.BUCKETS - 1) {
                "≥${i * OpusFrames.BUCKET_US / 1000.0}ms"
            } else {
                "${i * OpusFrames.BUCKET_US / 1000.0}ms"
            }
            val sb = tocHist ?: StringBuilder().also { tocHist = it }
            sb.append(name).append('=').append(n).append(' ')
        }
        if (tocPackets > 0 || tocUnparseableFrames > 0) {
            val unparseable = tocUnparseableFrames
            tocUnparseableFrames = 0
            Log.i(TAG, "AudioToc 30s: ${tocHist?.toString()?.trim().orEmpty()} unparseable=$unparseable")
        }
        val echo = echoProbe.snapshotAndReset()
        val ref = echoReference.snapshotAndReset()
        if (echo.farMs > 0 || ref.padFrames > 0 || ref.skipFrames > 0 || ref.resyncs > 0) {
            val erleAll = echo.erleAllDb?.let { String.format("%.1f", it) } ?: "n/a"
            val erleQuiet = echo.erleQuietDb?.let { String.format("%.1f", it) } ?: "n/a"
            Log.i(
                TAG,
                "AudioEcho 30s: farMs=${echo.farMs} erleAll=$erleAll erleQuiet=$erleQuiet " +
                    "pairs=${echo.pairedFrames} quiet=${echo.quietFrames} " +
                    "refPad=${ref.padFrames} refSkip=${ref.skipFrames} refResync=${ref.resyncs}",
            )
        }
        val an = echoProcSampleCount
        if (an > 0) {
            echoProcSampleCount = 0
            val samples = echoProcSamplesUs.take(an.coerceAtMost(echoProcSamplesUs.size)).sorted()
            val p95 = samples[(samples.size * 95 / 100).coerceIn(0, samples.size - 1)]
            Log.i(TAG, "AudioEchoProc 30s: n=${samples.size} p95=${p95}us max=${samples.last()}us (budget 20000us)")
        }
    }

    /** Record one encoder call duration (µs) into the diagnostics ring. */
    private fun recordEncodeUs(us: Long) {
        encodeSamplesUs[encodeSampleIdx and (encodeSamplesUs.size - 1)] = us
        encodeSampleIdx++
        encodeSampleCount++
    }

    /** Record one AEC process call duration (µs) into the diagnostics ring. */
    private fun recordEchoProcUs(us: Long) {
        echoProcSamplesUs[echoProcSampleIdx and (echoProcSamplesUs.size - 1)] = us
        echoProcSampleIdx++
        echoProcSampleCount++
    }

    /** Record one enqueue→playback latency (µs) into the diagnostics ring. */
    private fun recordQueueDelayUs(us: Long) {
        queueDelaySamplesUs[queueDelaySampleIdx and (queueDelaySamplesUs.size - 1)] = us
        queueDelaySampleIdx++
        queueDelaySampleCount++
    }

    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        val n = shorts.size
        val bytes = ByteArray(n * 2)
        for (i in 0 until n) {
            bytes[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (shorts[i].toInt() shr 8 and 0xFF).toByte()
        }
        return bytes
    }

    private fun bytesToShorts(bytes: ByteArray, out: ShortArray) =
        decodeBytesToShorts(bytes, out)
}

/**
 * Little-endian PCM bytes into [out], writing exactly the decoded sample
 * count — [out] is a decode buffer reused across frames AND across users,
 * and the mixer sums every sample of it, so whatever the decoder did not
 * fill must be zeroed: a short decode would otherwise fold the previous
 * frame's (or the previous speaker's) tail into the mix.
 * File-level and internal so the zero-fill contract is unit-testable.
 */
internal fun decodeBytesToShorts(bytes: ByteArray, out: ShortArray) {
    val count = minOf(bytes.size / 2, out.size)
    for (i in 0 until count) {
        out[i] = ((bytes[i * 2].toInt() and 0xFF) or
                (bytes[i * 2 + 1].toInt() shl 8)).toShort()
    }
    java.util.Arrays.fill(out, count, out.size, 0.toShort())
}
