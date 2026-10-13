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
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
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

    // Encoder timing ring (µs per encode call) — validates the complexity-8
    // CPU budget on real devices (frame budget is 20ms = 20000µs)
    private val encodeSamplesUs = LongArray(512)
    private var encodeSampleIdx = 0
    @Volatile
    private var encodeSampleCount = 0

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var acousticEchoCanceler: AcousticEchoCanceler? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
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
            // Voice-call audio path: comm mode routes playback to the call
            // volume stream (calibrated louder for speech), engages platform
            // comms loudness/DSP and auto-routes Bluetooth via SCO. Session
            // lifetime = this service's audio lifetime.
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
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
            // Field diagnostics: a silent session with the volume keys still
            // showing media almost always means this line says track=null or
            // callVol=0 — the init error (if any) is logged just above
            Log.i(
                TAG,
                "Voice path init: mode=${audioManager.mode} track=${audioTrack != null} " +
                    "callVol=${audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)}/" +
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
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
    fun startCapture(scope: CoroutineScope, noiseSuppressionEnabled: Boolean = true) {
        if (_isCapturing.value) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Cannot start capture: RECORD_AUDIO permission is missing")
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, FRAME_SIZE_BYTES * 4),
            )
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create AudioRecord", e)
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord is not initialized")
            record.release()
            return
        }
        try {
            record.startRecording()
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start microphone capture", e)
            record.release()
            return
        }
        audioRecord = record
        _isCapturing.value = true
        captureScope = scope
        requestAudioFocus()
        noiseSuppressor?.release()
        noiseSuppressor = null
        if (noiseSuppressionEnabled && NoiseSuppressor.isAvailable()) {
            try {
                NoiseSuppressor.create(record.audioSessionId)?.also {
                    noiseSuppressor = it
                    Log.i(TAG, "NoiseSuppressor enabled (session=${record.audioSessionId})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create NoiseSuppressor", e)
            }
        } else {
            Log.i(TAG, "NoiseSuppressor skipped: enabled=$noiseSuppressionEnabled, available=${NoiseSuppressor.isAvailable()}")
        }
        // Echo cancellation is independent of the noise-suppression toggle:
        // without it, speaker playback bleeds into the mic and remote users
        // hear themselves. VOICE_COMMUNICATION implies AEC on most devices,
        // but that is not guaranteed everywhere — attach it when offered.
        acousticEchoCanceler?.release()
        acousticEchoCanceler = null
        if (AcousticEchoCanceler.isAvailable()) {
            try {
                AcousticEchoCanceler.create(record.audioSessionId)?.also {
                    acousticEchoCanceler = it
                    Log.i(TAG, "AcousticEchoCanceler enabled (session=${record.audioSessionId})")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create AcousticEchoCanceler", e)
            }
        } else {
            Log.i(TAG, "AcousticEchoCanceler not available on this device")
        }

        captureJob = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(FRAME_SIZE_SAMPLES)
            val codec = encoder ?: run {
                Log.e(TAG, "Cannot start capture: Opus encoder is not initialized")
                _isCapturing.value = false
                return@launch
            }
            while (isActive && _isCapturing.value) {
                val read = try {
                    record.read(buffer, 0, FRAME_SIZE_SAMPLES)
                } catch (e: Throwable) {
                    Log.e(TAG, "Microphone read failed", e)
                    break
                }
                if (read < 0) {
                    Log.e(TAG, "Microphone read returned error $read")
                    break
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
            // Release only the local handle. The audioRecord/noiseSuppressor
            // fields belong to startCapture/stopCapture on the caller thread,
            // so this late cleanup can never clear or double-release a
            // replacement instance from a quick stop→start cycle.
            try {
                record.stop()
            } catch (_: Throwable) {
            }
            try {
                record.release()
            } catch (_: Throwable) {
            }
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
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        noiseSuppressor?.release()
        noiseSuppressor = null
        acousticEchoCanceler?.release()
        acousticEchoCanceler = null
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
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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
        noiseSuppressor?.release()
        noiseSuppressor = null
        acousticEchoCanceler?.release()
        acousticEchoCanceler = null
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
        // Leave the voice-call audio path
        audioManager.mode = AudioManager.MODE_NORMAL
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
    }

    /** Record one encoder call duration (µs) into the diagnostics ring. */
    private fun recordEncodeUs(us: Long) {
        encodeSamplesUs[encodeSampleIdx and (encodeSamplesUs.size - 1)] = us
        encodeSampleIdx++
        encodeSampleCount++
    }

    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            bytes[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (shorts[i].toInt() shr 8 and 0xFF).toByte()
        }
        return bytes
    }

    private fun bytesToShorts(bytes: ByteArray, out: ShortArray) {
        val count = minOf(bytes.size / 2, out.size)
        for (i in 0 until count) {
            out[i] = ((bytes[i * 2].toInt() and 0xFF) or
                    (bytes[i * 2 + 1].toInt() shl 8)).toShort()
        }
    }
}
