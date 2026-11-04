package dev.tsdroid.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
         * Output gain stage with a soft knee: linear below half scale
         * (unity listening is untouched), smoothly compressed above — an
         * boosted mix tops out near full scale instead of hard-clipping.
         * Slope-continuous at the knee (k=2 keeps the derivative at 1).
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

        /**
         * SCO carries voice at the headset rate (CVSD 8 kHz / mSBC 16 kHz).
         * Several HALs only advertise 8/16 kHz for the voice-call output, so a
         * 48 kHz voice track lands on a stream the SCO path never consumes:
         * head frozen at 0 with zero underruns while the track is routed to
         * the headset. Talk to the headset at 16 kHz and resample locally.
         */
        private const val COMM_SAMPLE_RATE = 16_000
        private const val COMM_RESAMPLE_FACTOR = SAMPLE_RATE / COMM_SAMPLE_RATE // 3
        private const val COMM_FRAME_SAMPLES = FRAME_SIZE_SAMPLES / COMM_RESAMPLE_FACTOR // 320
        private const val COMM_FRAME_BYTES = COMM_FRAME_SAMPLES * 2
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

        /** Comm-route playback head must advance within this under sustained writes. */
        private const val FROZEN_HEAD_TIMEOUT_MS = 3_000L

        /** Comm voice-track construction ladder length (see buildCommAudioTrack). */
        private const val COMM_TRACK_VARIANT_COUNT = 3

        /** Let the async audio-policy apply the mode/device switch before building the voice track. */
        private const val COMM_TRACK_SETTLE_MS = 400L

        /** How long the rebuilt voice track gets to report a real routed device. */
        private const val COMM_ROUTE_VERIFY_MS = 1_200L

        /**
         * Diagnostic route probe (debug builds only): write a continuous soft
         * tone into a freshly switched comm track. Far-end audio is usually
         * sparse right after a headset connects, and a track fed one 20 ms
         * frame every few seconds cannot prove whether the SCO output
         * consumes at all. The tone answers that in the log and in the ear.
         */
        private const val COMM_PROBE_MS = 3_000L
        private const val COMM_PROBE_HZ = 440.0
        private const val COMM_PROBE_AMPLITUDE = 2_000.0 // ~-24 dBFS
    }

    private val audioConfig = AudioConfig()

    // Encoder for capture (our mic)
    private var encoder: OpusCodec? = null

    // Per-user decoders and raw opus queues for playback mixing
    private val userDecoders = ConcurrentHashMap<Int, OpusCodec>()
    private val userQueues = ConcurrentHashMap<Int, FrameQueue>()

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
    private var lastDiagFlush = System.currentTimeMillis()

    /** Short-frame sightings this diagnostics window (decode-skip decision pending the count). */
    @Volatile
    private var comfortNoiseFrames = 0

    /** TOC frame-duration histogram this window (key = µs, value = packets). */
    private val tocDurationCounts = ConcurrentHashMap<Long, Int>()
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

    /** Live AEC handle for the zeroed-frame diagnostic (set/cleared by the capture loop). */
    @Volatile
    private var echoCancellerRef: EchoCanceller? = null

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
    @Volatile
    private var playbackWritesTotal = 0L

    /** Previous cumulative zeroed-frame total — playback thread only. */
    private var lastEchoZeroedTotal = -1L

    /**
     * Guards [audioRecord]/effect swaps between startCapture/stopCapture
     * (caller thread) and the capture loop's self-heal rebuilds, so a
     * rebuild finishing after stopCapture can never orphan a live recorder.
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

    /**
     * AudioTrack swaps follow the same discipline as capture swaps: the
     * field swap is atomic, the native release happens off the swap path,
     * and the playback loop tolerates one failed write during a switch.
     */
    private val trackSwapLock = Any()

    /** Bluetooth communication-route plumbing + audio-device watching. */
    private val routeManager = AudioRouteManager(context, audioManager, object : AudioRouteManager.Listener {
        override suspend fun onRouteChanged(comm: Boolean, deviceName: String?) {
            applyRoute(comm, deviceName)
        }

        override suspend fun onCommRoutePrepare() {
            releasePlaybackForRouteSwitch()
        }

        override suspend fun onCommRouteAborted() {
            Log.i(TAG, "Comm route aborted; restoring media playback")
            rebuildTrackForRoute(commRoute = false)
        }
    })

    /** True while track and recorder are built for the communication route. */
    @Volatile
    private var commRouteActive = false

    /** Diagnostic tone window after a comm-route switch (debug builds only). */
    private var commProbeUntil = 0L
    private var commProbePhase = 0.0

    /** Debug APKs get the route probe; release builds never emit the tone. */
    private val isDebuggable: Boolean =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Which voice-track construction the comm route is currently using.
     * Some ROMs initialize a voice-usage track successfully but the HAL
     * never consumes it (playback head frozen); the ladder tries
     * attributes+low-latency, attributes plain, then the legacy
     * STREAM_VOICE_CALL constructor before giving up to the media path.
     */
    @Volatile
    private var commTrackVariant = 0

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

    // Dedicated single-thread scope for audio playback
    @OptIn(ExperimentalCoroutinesApi::class)
    private val playbackScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1)
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
    }

    private val _isMuted = MutableStateFlow(true) // Start muted (PTT default)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isOutputMuted = MutableStateFlow(false)
    val isOutputMuted: StateFlow<Boolean> = _isOutputMuted.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _isLocalVoiceActive = MutableStateFlow(false)
    val isLocalVoiceActive: StateFlow<Boolean> = _isLocalVoiceActive.asStateFlow()

    /** Current audio routing (media path vs Bluetooth comm route), for the UI. */
    val routeState: StateFlow<AudioRouteState>
        get() = routeManager.routeState

    fun initialize() {
        try {
            // Diagnostic bisect (2026-10-13): the voice-call stream switch
            // (USAGE_VOICE_COMMUNICATION + MODE_IN_COMMUNICATION + comm-device
            // routing) is reverted to the proven media path — on this device
            // the comm-mode track played "dead" (playback head frozen, recv
            // pinned at 4 frames). The permission + diagnostics stay.
            // Explicitly release any old audio stream resources if lingering
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null

            encoder = OpusCodec(audioConfig)
            initAudioTrack()
            startPlaybackLoop()
            // Bluetooth route watching: the media path stays the default;
            // the manager only flips mode/devices when an SCO-capable
            // headset appears, and every transition comes back through
            // [applyRoute] for the track/recorder rebuilds
            routeManager.start()
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
        audioRecord = record
        _isCapturing.value = true
        captureScope = scope
        lastCaptureProgressAt = SystemClock.elapsedRealtime()
        requestAudioFocus()

        captureJob = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(FRAME_SIZE_SAMPLES)
            val codec = encoder ?: run {
                Log.e(TAG, "Cannot start capture: Opus encoder is not initialized")
                _isCapturing.value = false
                return@launch
            }
            var record = audioRecord ?: run {
                Log.e(TAG, "Cannot start capture: AudioRecord missing")
                _isCapturing.value = false
                return@launch
            }
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
            val aec = try {
                EchoCanceller(SAMPLE_RATE, FRAME_SIZE_SAMPLES, AEC_TAIL_SAMPLES).also {
                    echoReference.reset()
                    aecRunning = true
                    echoCancellerRef = it
                    Log.i(TAG, "Software AEC started (tail=${AEC_TAIL_MS}ms)")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Software AEC unavailable this session", t)
                null
            }
            val aecFar = ShortArray(FRAME_SIZE_SAMPLES)
            val aecOut = ShortArray(FRAME_SIZE_SAMPLES)
            var aecFailStreak = 0

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
                if (!_isCapturing.value || !isActive) return null
                consecutiveRebuilds++
                val fresh = createAudioRecord() ?: run {
                    Log.e(TAG, "Capture rebuild failed (attempt $consecutiveRebuilds); ending capture loop")
                    _isCapturing.value = false
                    return null
                }
                synchronized(captureSwapLock) {
                    if (!_isCapturing.value) {
                        // stopCapture ran while we were building — don't
                        // orphan a live recorder behind its back
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

            while (isActive && _isCapturing.value) {
                val read = try {
                    record.read(buffer, 0, FRAME_SIZE_SAMPLES)
                } catch (e: Throwable) {
                    Log.e(TAG, "Microphone read failed; attempting capture rebuild", e)
                    record = rebuildOrStop(record) ?: break
                    continue
                }
                if (read < 0) {
                    Log.w(TAG, "Microphone read error $read; attempting capture rebuild")
                    record = rebuildOrStop(record) ?: break
                    continue
                }
                if (read == 0) {
                    emptyReadStreak++
                    if (emptyReadStreak >= EMPTY_READ_STREAK_LIMIT) {
                        Log.w(TAG, "Microphone returned $emptyReadStreak empty reads; attempting capture rebuild")
                        record = rebuildOrStop(record) ?: break
                    } else if (emptyReadStreak == EMPTY_READ_STREAK_LIMIT / 2) {
                        // Early warning: short sessions die before the 30s flush sees anything
                        Log.w(TAG, "Microphone empty reads at $emptyReadStreak (half of rebuild limit)")
                    }
                    continue
                }
                emptyReadStreak = 0
                if (read == FRAME_SIZE_SAMPLES) {
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
                    if (aec != null) {
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
                    }
                    // ERLE probe observes the post-AEC mic — what actually gets
                    // transmitted; unaffected by mute (which only gates the
                    // encoder).
                    echoProbe.onMicFrame(buffer, System.currentTimeMillis() < farActiveUntil)
                } else {
                    // Partial read (0 < read < frame): still proves the device is alive
                    lastCaptureProgressAt = SystemClock.elapsedRealtime()
                }
                if (read == FRAME_SIZE_SAMPLES && !_isMuted.value && micGainFactor != 0f) {
                    // Mic gain is applied before VAD and encode so "turn it up
                    // and be heard" behaves uniformly in both mic modes
                    val gain = micGainFactor
                    if (gain != 1f) {
                        for (i in 0 until read) {
                            buffer[i] = softClip(buffer[i] * gain)
                        }
                    }
                    if (micMode == MicMode.VAD) {
                        // Gate transmission on the voice-activation state machine
                        vadGate.openThresholdDb = vadThresholdDb
                        val toSend = vadGate.process(buffer, System.currentTimeMillis())
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
                        for (i in 0 until read) {
                            energy += buffer[i].toLong() * buffer[i].toLong()
                        }
                        val rms = Math.sqrt(energy.toDouble() / read)
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
            _isCapturing.value = false
            _isLocalVoiceActive.value = false
            // Stop the reference feed before tearing the engine down; both
            // are synchronized, so a concurrent playback push can never touch
            // a destroyed native handle.
            aecRunning = false
            try {
                aec?.close()
            } catch (_: Throwable) {}
            echoCancellerRef = null
            echoReference.reset()
            // Release only the local handle. The audioRecord field belongs to
            // startCapture/stopCapture on the caller thread, so this late
            // cleanup can never clear or double-release a replacement
            // instance from a quick stop→start cycle.
            recorderStopper.stopAndRelease(record)
        }
    }

    fun stopCapture() {
        _isCapturing.value = false
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
     * Create and start a VOICE_COMMUNICATION recorder, or null on failure.
     * Shared by [startCapture] and the capture loop's self-heal rebuilds.
     */
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
        if (commRouteActive) {
            // Communication route: the SCO input only routes reliably for a
            // communication source — raw sources stay pinned to the built-in
            // mic on several HALs even with a preferred device set
            val sco = routeManager.scoInputDevice()
            Log.i(TAG, "Capture source: comm route (sco=${sco != null})")
            return tryCreateAudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, minBuf, sco)
                ?: tryCreateAudioRecord(MediaRecorder.AudioSource.MIC, minBuf, sco)
        }
        // Requested raw source first, then MIC, then the platform voice
        // source as the last resort: the all-software pipeline wants an
        // unprocessed signal (vendor voice chains nonlinearly process the
        // echo path), and a silent fallback beats a dead capture loop
        val requested = captureSource.resolveSourceId()
        Log.i(TAG, "Capture source: ${captureSource.raw} (id=$requested)")
        return tryCreateAudioRecord(requested, minBuf, null)
            ?: if (requested != MediaRecorder.AudioSource.MIC) {
                tryCreateAudioRecord(MediaRecorder.AudioSource.MIC, minBuf, null)
            } else {
                null
            }
            ?: if (requested != MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
                tryCreateAudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, minBuf, null)
            } else {
                null
            }
    }

    private fun tryCreateAudioRecord(
        sourceId: Int,
        minBuf: Int,
        preferredDevice: AudioDeviceInfo?,
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
        if (preferredDevice != null && !record.setPreferredDevice(preferredDevice)) {
            // Routing may still land correctly via the comm route; keep the
            // recorder rather than failing the whole capture session
            Log.w(TAG, "setPreferredDevice(sco) rejected (source=$sourceId); relying on route")
        }
        return try {
            record.startRecording()
            Log.i(TAG, "Capture started (source=$sourceId, sco=${preferredDevice != null})")
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
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        audioTrack = buildAudioTrack(minBuf, lowLatency = true, commRoute = false)
        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            // Some devices reject PERFORMANCE_MODE_LOW_LATENCY for voice-usage
            // tracks — fall back to a plain track rather than going silent
            Log.w(TAG, "Low-latency voice track not initialized; retrying without performance mode")
            audioTrack?.release()
            audioTrack = buildAudioTrack(minBuf, lowLatency = false, commRoute = false)
        }
        audioTrack?.play()
    }

    private fun buildAudioTrack(minBuf: Int, lowLatency: Boolean, commRoute: Boolean): AudioTrack {
        // Comm route: a voice-usage track is what follows
        // setCommunicationDevice to the headset; on the media route the
        // proven USAGE_GAME stream keeps its volume curve and routing
        val usage = if (commRoute) {
            AudioAttributes.USAGE_VOICE_COMMUNICATION
        } else {
            AudioAttributes.USAGE_GAME
        }
        val rate = if (commRoute) COMM_SAMPLE_RATE else SAMPLE_RATE
        val frameBytes = if (commRoute) COMM_FRAME_BYTES else FRAME_SIZE_BYTES
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(usage)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, frameBytes * TRACK_BUFFER_FRAMES))
        if (lowLatency) {
            builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        }
        return builder.build()
    }

    /**
     * Stop and release the media track before the communication route starts.
     * While an A2DP-routed track is active the headset keeps its audio path in
     * music mode and never switches to HFP — SCO then connects but carries no
     * audio in either direction.
     */
    private suspend fun releasePlaybackForRouteSwitch() {
        val old = synchronized(trackSwapLock) {
            val t = audioTrack
            audioTrack = null
            t
        } ?: return
        Log.i(TAG, "Releasing media track before comm route (A2DP -> idle)")
        withContext(Dispatchers.IO) {
            try {
                old.stop()
                old.release()
            } catch (t: Throwable) {
                Log.w(TAG, "Media track release before comm route failed (continuing)", t)
            }
        }
    }

    /**
     * Swap the playback track for the new route. The field swap is atomic
     * under [trackSwapLock]; the old native track is stopped/released off
     * the swap path so the playback loop never blocks on teardown (same
     * discipline as capture swaps). One in-flight write may fail against
     * the released track — the loop catches that and re-reads the field.
     */
    private fun rebuildTrackForRoute(commRoute: Boolean) {
        val rate = if (commRoute) COMM_SAMPLE_RATE else SAMPLE_RATE
        val minBuf = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (commRoute) resetCommResampler()
        val old = synchronized(trackSwapLock) {
            val t = audioTrack
            audioTrack = null
            t
        }
        var track: AudioTrack
        if (commRoute) {
            track = buildCommAudioTrack(minBuf, commTrackVariant)
        } else {
            track = buildAudioTrack(minBuf, lowLatency = true, commRoute = false)
        }
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "Track not initialized for the new route; plain fallback")
            track.release()
            track = if (commRoute) {
                buildCommAudioTrack(minBuf, COMM_TRACK_VARIANT_COUNT - 1)
            } else {
                buildAudioTrack(minBuf, lowLatency = false, commRoute = false)
            }
        }
        track.play()
        synchronized(trackSwapLock) { audioTrack = track }
        if (old != null) {
            Thread {
                try {
                    old.stop()
                    old.release()
                } catch (t: Throwable) {
                    Log.w(TAG, "Old playback track release failed (continuing)", t)
                }
            }.start()
        }
        Log.i(
            TAG,
            "Playback track rebuilt for ${if (commRoute) "comm" else "media"} route " +
                "(${if (commRoute) "variant=$commTrackVariant" else "audio"})",
        )
    }

    /**
     * Comm-route voice track, one ladder step per variant:
     * 0 = attributes USAGE_VOICE_COMMUNICATION + low-latency performance,
     * 1 = same attributes without the performance mode,
     * 2 = legacy STREAM_VOICE_CALL constructor.
     * Some ROMs initialize 0/1 fine but never consume them (frozen head);
     * the playback loop walks the ladder on the frozen-head fingerprint.
     */
    private fun buildCommAudioTrack(minBuf: Int, variant: Int): AudioTrack {
        return if (variant >= 2) {
            buildLegacyVoiceTrack(minBuf)
        } else {
            buildAudioTrack(minBuf, lowLatency = variant == 0, commRoute = true)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildLegacyVoiceTrack(minBuf: Int): AudioTrack {
        return AudioTrack(
            AudioManager.STREAM_VOICE_CALL,
            COMM_SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, COMM_FRAME_BYTES * TRACK_BUFFER_FRAMES),
            AudioTrack.MODE_STREAM,
        )
    }

    /**
     * Route transition: rebuild the playback track and the capture pipeline
     * for the new route. Order follows the 2026-10-13 remedy list — the
     * manager has already applied mode + comm device; the track must be
     * destroyed and rebuilt after any route change, and the recorder after
     * it. The switch window mutes playback (queues cleared) so the swap
     * never plays through a half-configured track.
     */
    private suspend fun applyRoute(comm: Boolean, deviceName: String?) {
        if (comm == commRouteActive) return
        Log.i(
            TAG,
            "Route switch: ${if (comm) "comm($deviceName)" else "media"} " +
                "(was ${if (commRouteActive) "comm" else "media"})",
        )
        suspendOutput(true)
        try {
            commRouteActive = comm
            if (comm) {
                // The ROM can reset mode/comm device during SCO setup; the
                // track must be built against the asserted state or it lands
                // on the media stream and never reaches the headset
                routeManager.reassertCommPlumbing()
                // The audio policy applies mode/device changes asynchronously;
                // a voice track created inside that window initializes fine
                // but is never consumed by the HAL (observed on api 37)
                delay(COMM_TRACK_SETTLE_MS)
            }
            rebuildTrackForRoute(comm)
            if (comm && isDebuggable) {
                commProbeUntil = SystemClock.elapsedRealtime() + COMM_PROBE_MS
                commProbePhase = 0.0
                Log.i(TAG, "Comm route probe armed (${COMM_PROBE_MS}ms ${COMM_PROBE_HZ.toInt()}Hz tone)")
            }
            if (_isCapturing.value) {
                val scope = captureScope
                if (scope != null) {
                    stopCapture()
                    startCapture(scope)
                }
            }
        } finally {
            suspendOutput(false)
        }
        if (!comm) return
        // Platform truth check: the voice track must actually be attached to
        // the headset. A track the policy routed anywhere else reproduces the
        // "silent headset" bug while setCommunicationDevice() still returns
        // true. Route attachment happens on the first writes, so verify after
        // the output window reopened.
        val routed = awaitTrackRoute(COMM_ROUTE_VERIFY_MS)
        when {
            routed == null -> Log.w(
                TAG,
                "Comm track route unknown after ${COMM_ROUTE_VERIFY_MS}ms " +
                    "(no audio written yet); frozen-head watchdog stays armed",
            )
            !isCommAudioDevice(routed) -> {
                val reason = "comm track routed to type=${routed.type} instead of the headset"
                Log.e(TAG, reason)
                routeManager.forceMediaFallback(reason)
            }
            else -> Log.i(
                TAG,
                "Comm track routed to type=${routed.type} name=${routed.productName}",
            )
        }
    }

    /** Poll the live track's actual route; null while the policy has not attached one yet. */
    private suspend fun awaitTrackRoute(timeoutMs: Long): AudioDeviceInfo? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val track = synchronized(trackSwapLock) { audioTrack }
            val routed = try {
                track?.routedDevice
            } catch (_: Throwable) {
                null
            }
            if (routed != null) return routed
            delay(100)
        }
        return null
    }

    private fun isCommAudioDevice(device: AudioDeviceInfo): Boolean =
        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)

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
    private fun startPlaybackLoop() {
        playbackJob = playbackScope.launch {
            val mixBuffer = ShortArray(FRAME_SIZE_SAMPLES)
            val decodeBuffer = ShortArray(FRAME_SIZE_SAMPLES)
            val commBuffer = ShortArray(COMM_FRAME_SAMPLES)
            // Total PCM frames handed to AudioTrack — compared against
            // playbackHeadPosition to measure the track's buffered depth.
            // Reset whenever the track instance changes: a fresh track's
            // head starts at 0 while this counter is cumulative, and the
            // mismatch reads as an enormous phantom backlog that would stop
            // all writes after every route switch.
            var writtenFrames = 0L
            var currentTrack: AudioTrack? = null

            // Frozen-head watch ("dead track" fingerprint). A healthy track
            // consumes every frame promptly, so: at least one frame written
            // since the head last advanced, and the head still static after
            // the timeout = dead. Deliberately NO recency-of-write
            // requirement — a dead track lets the loop stall on the
            // over-buffer guard, which itself stops the writes and used to
            // make this check unable to ever fire again (ladder got stuck).
            // No writes since the head last moved (idle, nothing playing)
            // is a healthy static head and never trips.
            var headWatchValue = -1L
            var headWatchSince = 0L
            var framesSinceHeadMove = 0L

            // Sum one decoded frame into the mix buffer with per-user gain
            fun mixIn(userId: Int, pcm: ByteArray): Boolean {
                return try {
                    bytesToShorts(pcm, decodeBuffer)
                    val factor = userGainFactors[userId]
                    if (factor != null && factor != 1f) {
                        for (i in mixBuffer.indices) {
                            val sum = mixBuffer[i] + (decodeBuffer[i] * factor).toInt()
                            mixBuffer[i] = sum.coerceIn(
                                Short.MIN_VALUE.toInt(),
                                Short.MAX_VALUE.toInt(),
                            ).toShort()
                        }
                    } else {
                        // Mix: sum with clipping
                        for (i in mixBuffer.indices) {
                            val sum = mixBuffer[i].toInt() + decodeBuffer[i].toInt()
                            mixBuffer[i] = sum.coerceIn(
                                Short.MIN_VALUE.toInt(),
                                Short.MAX_VALUE.toInt(),
                            ).toShort()
                        }
                    }
                    true
                } catch (_: Exception) {
                    false
                }
            }

            while (isActive) {
                var hasData = false
                mixBuffer.fill(0)
                val now = System.currentTimeMillis()

                if (now - lastDiagFlush >= DIAG_FLUSH_MS) {
                    lastDiagFlush = now
                    flushAudioDiag()
                }

                if (commProbeUntil != 0L && SystemClock.elapsedRealtime() >= commProbeUntil) {
                    val probeTrack = synchronized(trackSwapLock) { audioTrack }
                    Log.i(
                        TAG,
                        "Comm route probe done: head=${probeTrack?.playbackHeadPosition ?: -1} " +
                            "playState=${probeTrack?.playState ?: -1} " +
                            "underruns=${probeTrack?.underrunCount ?: -1}",
                    )
                    commProbeUntil = 0L
                }

                // Write-side latency watermark: AudioTrack drains in real
                // time while this loop writes at decode pace — without a cap,
                // any burst piles up inside the track buffer and lingers as
                // sustained latency. Capacity is headroom, this is the bound.
                val trackNow = synchronized(trackSwapLock) { audioTrack }
                if (trackNow !== currentTrack) {
                    currentTrack = trackNow
                    writtenFrames = 0L
                    headWatchValue = -1L
                    headWatchSince = 0L
                    framesSinceHeadMove = 0L
                }
                if (trackNow == null) {
                    // Route switch window: the media track is released and the
                    // new one is still being built — nothing to write into
                    playbackWake.poll(10, java.util.concurrent.TimeUnit.MILLISECONDS)
                    continue
                }
                val head = trackNow.playbackHeadPosition.toLong()
                // Track-rate aware depth: the comm voice track runs at the
                // headset rate (16 kHz), the media track at 48 kHz
                val trackRate = trackNow?.sampleRate ?: SAMPLE_RATE
                val bufferedMs = (writtenFrames - head) * 1000 / trackRate
                if (bufferedMs > trackBufPeakMs) trackBufPeakMs = bufferedMs

                // Frozen-head watch runs BEFORE the over-buffer guard: a dead
                // track makes the guard stall this loop itself (write counter
                // frozen, head static), so a watch placed after the guard can
                // never fire for exactly the case it must catch.
                if (commRouteActive) {
                    val nowMs = SystemClock.elapsedRealtime()
                    if (head != headWatchValue) {
                        headWatchValue = head
                        headWatchSince = nowMs
                        framesSinceHeadMove = 0L
                    } else if (headWatchSince != 0L &&
                        framesSinceHeadMove > 0 &&
                        nowMs - headWatchSince > FROZEN_HEAD_TIMEOUT_MS
                    ) {
                        val trackRef = trackNow
                        if (commTrackVariant < COMM_TRACK_VARIANT_COUNT - 1) {
                            // Dead track on this ROM — walk the construction
                            // ladder before surrendering the headset
                            commTrackVariant++
                            Log.w(
                                TAG,
                                "Comm track not consuming (head=$head writes=$playbackWritesTotal " +
                                    "framesSinceMove=$framesSinceHeadMove " +
                                    "playState=${trackRef?.playState ?: -1} underruns=${trackRef?.underrunCount ?: -1}); " +
                                    "retrying variant $commTrackVariant",
                            )
                            rebuildTrackForRoute(commRoute = true)
                        } else {
                            Log.e(
                                TAG,
                                "All comm track variants dead (head=$head writes=$playbackWritesTotal " +
                                    "framesSinceMove=$framesSinceHeadMove " +
                                    "playState=${trackRef?.playState ?: -1} underruns=${trackRef?.underrunCount ?: -1}) " +
                                    "— forcing media fallback",
                            )
                            routeManager.forceMediaFallback("playback head frozen (comm track)")
                        }
                        headWatchValue = -1L
                        headWatchSince = 0L
                        framesSinceHeadMove = 0L
                    }
                } else {
                    headWatchValue = -1L
                    headWatchSince = 0L
                    framesSinceHeadMove = 0L
                }

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
                    val decoder = userDecoders.getOrPut(userId) { OpusCodec(audioConfig) }
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

                if (isDebuggable && commRouteActive &&
                    SystemClock.elapsedRealtime() < commProbeUntil
                ) {
                    // Diagnostic-only: continuous tone so the SCO output has
                    // data to consume even while the far end is quiet
                    val step = 2.0 * Math.PI * COMM_PROBE_HZ / SAMPLE_RATE
                    for (i in mixBuffer.indices) {
                        mixBuffer[i] = (Math.sin(commProbePhase + i * step) * COMM_PROBE_AMPLITUDE)
                            .toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            .toShort()
                    }
                    commProbePhase = (commProbePhase + mixBuffer.size * step) % (2.0 * Math.PI)
                    hasData = true
                }

                if (hasData) {
                    // Focus ducking multiplies the user gain — audio stays
                    // faintly audible under another app's short prompt
                    val gain = gainFactor * (if (duckedByFocus) DUCK_FACTOR else 1f)
                    if (gain != 1.0f) {
                        for (i in mixBuffer.indices) {
                            mixBuffer[i] = softClip(mixBuffer[i] * gain)
                        }
                    }
                    val track = synchronized(trackSwapLock) { audioTrack }
                    // Comm voice track runs at the headset rate: decimate the
                    // 48 kHz mix instead of handing the HAL a rate it will not
                    // carry over SCO
                    val bytes = if (track != null && track.sampleRate == COMM_SAMPLE_RATE) {
                        downmixToComm(mixBuffer, commBuffer)
                        shortsToBytes(commBuffer, COMM_FRAME_SAMPLES)
                    } else {
                        shortsToBytes(mixBuffer)
                    }
                    val written = try {
                        track?.write(bytes, 0, bytes.size) ?: -1
                    } catch (t: IllegalStateException) {
                        // Track swapped mid-write during a route switch —
                        // drop this frame; the next iteration picks up the
                        // fresh track
                        Log.w(TAG, "Write hit a swapped-out track (route switch)", t)
                        -1
                    }
                    if (written != bytes.size) {
                        // Short/error writes are a silent-session risk on
                        // route changes — surfaced in the 30s diagnostics
                        writeFailCount++
                        if (writeFailCount == 1) {
                            Log.w(TAG, "AudioTrack.write returned $written of ${bytes.size}")
                        }
                    }
                    val framesWritten = bytes.size / 2
                    writtenFrames += framesWritten
                    framesSinceHeadMove += framesWritten
                    playbackWritesTotal += framesWritten
                    // AEC reference: exactly the PCM handed to the speaker
                    // (post-gain, post-duck, post-softClip) — feeding the raw
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
            tocDurationCounts.merge(durationUs, 1, Int::plus)
        }
        if (userId in mutedUserIds) { discardMutedUser++; return }
        if (_isOutputMuted.value) { discardOutputMuted++; return } // Global output mute — discard incoming audio
        if (outputSuspendedByFocus) { discardSuspended++; return } // Another app holds focus — discard instead of queueing
        if (gainFactor == 0f) { discardGainZero++; return } // Speaker slider at 0% — same as muted
        val queue = userQueues.getOrPut(userId) { FrameQueue(MAX_QUEUE_FRAMES) }
        // Full queue evicts the OLDEST frame: fresh speech beats stale speech
        queue.offer(opusData, seq)
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
        routeManager.stop()
        stopCapture()
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
    }

    /** Emit the 30s receive/encode diagnostic aggregates into AppLog. */
    private fun flushAudioDiag() {
        /** Zeroed-frame delta over the window; -1 = unknown (no AEC / old .so). */
        fun readEchoZeroedDelta(): Long {
            val aec = echoCancellerRef ?: run {
                lastEchoZeroedTotal = -1L
                return -1L
            }
            val total = try {
                aec.zeroedFrames()
            } catch (_: Throwable) {
                lastEchoZeroedTotal = -1L
                return -1L
            }
            if (total < 0) return -1L // mixed-version native library
            if (lastEchoZeroedTotal < 0) {
                lastEchoZeroedTotal = total
                return 0L
            }
            val delta = total - lastEchoZeroedTotal
            lastEchoZeroedTotal = total
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
            // distinguishes the headset mic from a silent fallback to the
            // phone mic, which our own bookkeeping cannot tell apart
            val recRouteType = try {
                audioRecord?.routedDevice?.type ?: -1
            } catch (_: Throwable) {
                -1
            }
            Log.i(
                TAG,
                "AudioEnc 30s: n=${samples.size} p95=${p95}us max=${samples.last()}us " +
                    "(budget 20000us) micGain=$micGainFactor mode=$micMode vadDb=$vadThresholdDb " +
                    "route=${if (commRouteActive) "comm" else "media"} recRoute=$recRouteType " +
                    "callVol=${audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)} " +
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
            val head = outTrack?.playbackHeadPosition?.toLong() ?: -1L
            val playState = outTrack?.playState ?: -1
            val commDevType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.communicationDevice?.type ?: -1
            } else {
                -1
            }
            // Actual playback route (not our intent): the voice track must be
            // attached to the headset for any of this to be audible
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
                    "callVol=${audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)}/" +
                    "${audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)} " +
                    "musicVol=${audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)} " +
                    "mode=${audioManager.mode} " +
                    "suspended=$outputSuspendedByFocus ducked=$duckedByFocus " +
                    "route=${if (commRouteActive) "comm" else "media"} commDev=$commDevType " +
                    "sco=${routeManager.scoStateLabel()} trackRoute=$trackRouteType " +
                    "rate=${outTrack?.sampleRate ?: -1} " +
                    "variant=$commTrackVariant " +
                    "discard[susp=$discardSuspended outMuted=$discardOutputMuted gain0=$discardGainZero userMuted=$discardMutedUser] " +
                    "writeFail=$writeFailCount",
            )
            trackBufPeakMs = 0
            discardSuspended = 0
            discardOutputMuted = 0
            discardGainZero = 0
            discardMutedUser = 0
            writeFailCount = 0
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
        if (tocDurationCounts.isNotEmpty() || tocUnparseableFrames > 0) {
            val hist = tocDurationCounts.entries.sortedBy { it.key }
                .joinToString(" ") { "${it.key / 1000.0}ms=${it.value}" }
            Log.i(TAG, "AudioToc 30s: $hist unparseable=$tocUnparseableFrames")
            tocDurationCounts.clear()
            tocUnparseableFrames = 0
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

    // 48 kHz → 16 kHz anti-alias for the SCO voice track: two cascaded
    // 2nd-order low-passes at 7 kHz, then keep every third sample. State is
    // owned by the single playback thread.
    private val commLp1 = Biquad(7_000.0, SAMPLE_RATE)
    private val commLp2 = Biquad(7_000.0, SAMPLE_RATE)

    private fun resetCommResampler() {
        commLp1.reset()
        commLp2.reset()
    }

    /** Low-pass the 48 kHz mix and decimate by 3 → one 16 kHz voice frame. */
    private fun downmixToComm(src: ShortArray, dst: ShortArray) {
        var out = 0
        for (i in src.indices) {
            val y = commLp2.process(commLp1.process(src[i].toFloat()))
            if (i % COMM_RESAMPLE_FACTOR == 0 && out < dst.size) {
                dst[out++] = y.toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
        }
        while (out < dst.size) dst[out++] = 0
    }

    /** RBJ-cookbook biquad low-pass (mono, single writer). */
    private class Biquad(cutoffHz: Double, sampleRate: Int) {
        private val b0: Float
        private val b1: Float
        private val b2: Float
        private val a1: Float
        private val a2: Float
        private var x1 = 0f
        private var x2 = 0f
        private var y1 = 0f
        private var y2 = 0f

        init {
            val w0 = 2.0 * Math.PI * cutoffHz / sampleRate
            val cosW = Math.cos(w0)
            val alpha = Math.sin(w0) / (2.0 * Math.sqrt(0.5)) // Q = 0.707
            val a0 = 1.0 + alpha
            b0 = (((1.0 - cosW) / 2.0) / a0).toFloat()
            b1 = ((1.0 - cosW) / a0).toFloat()
            b2 = b0
            a1 = ((-2.0 * cosW) / a0).toFloat()
            a2 = ((1.0 - alpha) / a0).toFloat()
        }

        fun process(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = x
            y2 = y1
            y1 = y
            return y
        }

        fun reset() {
            x1 = 0f
            x2 = 0f
            y1 = 0f
            y2 = 0f
        }
    }

    private fun shortsToBytes(shorts: ShortArray, length: Int = shorts.size): ByteArray {
        val n = length.coerceIn(0, shorts.size)
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
