package dev.tsdroid.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
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
     * Guards [audioRecord]/effect swaps between startCapture/stopCapture
     * (caller thread) and the capture loop's self-heal rebuilds, so a
     * rebuild finishing after stopCapture can never orphan a live recorder.
     */
    private val captureSwapLock = Any()

    /** Requested capture source for the next recorder (re-evaluated per rebuild). */
    @Volatile
    private var captureSourcePref = CaptureSource.VOICE_PERFORMANCE

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
    fun startCapture(
        scope: CoroutineScope,
        captureSource: CaptureSource = CaptureSource.VOICE_PERFORMANCE,
    ) {
        if (_isCapturing.value) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Cannot start capture: RECORD_AUDIO permission is missing")
            return
        }

        // Apply the capture preferences BEFORE creating the recorder: the
        // source setting must govern this very capture. (These used to be
        // assigned after createAudioRecord, so a settings change took effect
        // one session late — the stale source silently ran until the first
        // rebuild swapped it, making the capture intermittently mute.)
        captureSourcePref = captureSource
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
                    // ERLE probe observes the raw mic — before gain/VAD/encode,
                    // unaffected by mute (which only gates transmission)
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
                                try {
                                    val t0 = System.nanoTime()
                                    val encoded = codec.encode(shortsToBytes(frame))
                                    recordEncodeUs((System.nanoTime() - t0) / 1000)
                                    tsClient.sendAudio(encoded, CODEC_OPUS_VOICE)
                                } catch (_: Exception) {}
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
                        try {
                            val t0 = System.nanoTime()
                            val encoded = codec.encode(pcmBytes)
                            recordEncodeUs((System.nanoTime() - t0) / 1000)
                            tsClient.sendAudio(encoded, CODEC_OPUS_VOICE)
                        } catch (_: Exception) {}
                    }
                } else {
                    vadGate.reset()
                    _isLocalVoiceActive.value = false
                }
            }
            _isCapturing.value = false
            _isLocalVoiceActive.value = false
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
        // Requested raw source first, then MIC, then the platform voice
        // source as the last resort: the all-software pipeline wants an
        // unprocessed signal (vendor voice chains nonlinearly process the
        // echo path), and a silent fallback beats a dead capture loop
        val requested = captureSourcePref.resolveSourceId()
        Log.i(TAG, "Capture source: ${captureSourcePref.raw} (id=$requested)")
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

    private fun tryCreateAudioRecord(sourceId: Int, minBuf: Int): AudioRecord? {
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
        audioTrack = buildAudioTrack(minBuf, lowLatency = true)
        if (audioTrack?.state != AudioTrack.STATE_INITIALIZED) {
            // Some devices reject PERFORMANCE_MODE_LOW_LATENCY for voice-usage
            // tracks — fall back to a plain track rather than going silent
            Log.w(TAG, "Low-latency voice track not initialized; retrying without performance mode")
            audioTrack?.release()
            audioTrack = buildAudioTrack(minBuf, lowLatency = false)
        }
        audioTrack?.play()
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
            // Total PCM frames handed to AudioTrack — compared against
            // playbackHeadPosition to measure the track's buffered depth
            var writtenFrames = 0L

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

                // Write-side latency watermark: AudioTrack drains in real
                // time while this loop writes at decode pace — without a cap,
                // any burst piles up inside the track buffer and lingers as
                // sustained latency. Capacity is headroom, this is the bound.
                val head = audioTrack?.playbackHeadPosition?.toLong() ?: 0L
                val bufferedMs = (writtenFrames - head) * 1000 / SAMPLE_RATE
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

                if (hasData) {
                    // Focus ducking multiplies the user gain — audio stays
                    // faintly audible under another app's short prompt
                    val gain = gainFactor * (if (duckedByFocus) DUCK_FACTOR else 1f)
                    if (gain != 1.0f) {
                        for (i in mixBuffer.indices) {
                            mixBuffer[i] = softClip(mixBuffer[i] * gain)
                        }
                    }
                    val bytes = shortsToBytes(mixBuffer)
                    audioTrack?.write(bytes, 0, bytes.size)
                    writtenFrames += FRAME_SIZE_SAMPLES
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
        // Count short comfort-noise frames ahead of every gate so the flow
        // shape is visible before any decode-skip behavior is decided —
        // behavior itself is unchanged until the numbers are in.
        if (opusData.size <= COMFORT_NOISE_FRAME_MAX_BYTES) {
            comfortNoiseFrames++
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
        if (userId in mutedUserIds) return
        if (_isOutputMuted.value) return // Global output mute — discard incoming audio
        if (outputSuspendedByFocus) return // Another app holds focus — discard instead of queueing
        if (gainFactor == 0f) return // Speaker slider at 0% — same as muted
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
            Log.i(TAG, "AudioEnc 30s: n=${samples.size} p95=${p95}us max=${samples.last()}us (budget 20000us)")
        }
        if (trackBufPeakMs > 0) {
            Log.i(TAG, "AudioOut 30s: trackBufPeak=${trackBufPeakMs}ms (target ${TARGET_TRACK_BUFFER_MS}ms)")
            trackBufPeakMs = 0
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
        if (echo.farMs > 0) {
            val erleAll = echo.erleAllDb?.let { String.format("%.1f", it) } ?: "n/a"
            val erleQuiet = echo.erleQuietDb?.let { String.format("%.1f", it) } ?: "n/a"
            Log.i(TAG, "AudioEcho 30s: farMs=${echo.farMs} erleAll=$erleAll erleQuiet=$erleQuiet pairs=${echo.pairedFrames} quiet=${echo.quietFrames}")
        }
    }

    /** Record one encoder call duration (µs) into the diagnostics ring. */
    private fun recordEncodeUs(us: Long) {
        encodeSamplesUs[encodeSampleIdx and (encodeSamplesUs.size - 1)] = us
        encodeSampleIdx++
        encodeSampleCount++
    }

    /** Record one enqueue→playback latency (µs) into the diagnostics ring. */
    private fun recordQueueDelayUs(us: Long) {
        queueDelaySamplesUs[queueDelaySampleIdx and (queueDelaySamplesUs.size - 1)] = us
        queueDelaySampleIdx++
        queueDelaySampleCount++
    }

    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
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
