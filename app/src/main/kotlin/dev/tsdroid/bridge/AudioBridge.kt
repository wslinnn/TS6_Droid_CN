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
        private const val FRAME_SIZE_MS = 20
        private const val FRAME_SIZE_SAMPLES = SAMPLE_RATE * FRAME_SIZE_MS / 1000 // 960
        private const val FRAME_SIZE_BYTES = FRAME_SIZE_SAMPLES * 2 // 16-bit PCM = 2 bytes/sample
        private const val MAX_QUEUE_FRAMES = 10 // Max buffered frames per user

        /** Output volume multiplier while another app holds transient focus (can-duck). */
        private const val DUCK_FACTOR = 0.3f

        /** Never adapt the voice bitrate below this — speech intelligibility floor. */
        private const val MIN_ADAPTIVE_BITRATE = 16_000

        /** Encoder FEC stays disabled below this observed loss fraction. */
        private const val FEC_ENABLE_LOSS = 0.02f
    }

    private val audioConfig = AudioConfig()

    // Encoder for capture (our mic)
    private var encoder: OpusCodec? = null

    // Per-user decoders and raw opus queues for playback mixing
    private val userDecoders = ConcurrentHashMap<Int, OpusCodec>()
    private val userQueues = ConcurrentHashMap<Int, ArrayDeque<ByteArray>>()

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

    // Per-user playback gain in dB (keyed by client id, session-scoped)
    private val userGainsDb = ConcurrentHashMap<Int, Float>()

    fun setUserGainDb(userId: Int, db: Float) {
        if (db == 0f) userGainsDb.remove(userId) else userGainsDb[userId] = db
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
        userGainsDb.keys.retainAll(activeIds)
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
            // Push-mode receive: frames are invoked from the native pump the
            // moment they arrive, skipping the event flow entirely
            tsClient.setAudioSink(audioSink)
        } catch (e: Exception) {
            android.util.Log.e("TS6_DEBUG", "Caught audio initialization friction safely", e)
            // We don't throw here to prevent JE_AppCustomException
        }
    }

    /** Native-side callback — queues the frame and wakes the playback loop. */
    private val audioSink = object : dev.tslib.AudioSink {
        override fun onAudioFrame(userId: Int, data: ByteArray, isWhisper: Boolean) {
            playAudio(userId, data)
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
                if (read == FRAME_SIZE_SAMPLES && !_isMuted.value) {
                    if (micMode == MicMode.VAD) {
                        // Gate transmission on the voice-activation state machine
                        vadGate.openThresholdDb = vadThresholdDb
                        val toSend = vadGate.process(buffer, System.currentTimeMillis())
                        if (toSend != null) {
                            for (frame in toSend) {
                                try {
                                    tsClient.sendAudio(codec.encode(shortsToBytes(frame)), CODEC_OPUS_VOICE)
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
                            val encoded = codec.encode(pcmBytes)
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
                synchronized(queue) { queue.clear() }
            }
        }
    }

    /**
     * Adapt the Opus encoder to observed network quality. The quality
     * monitor calls this with the recent packet-loss fraction; the encoder
     * scales FEC redundancy and sheds bitrate rather than flooding a weak
     * link with large frames.
     */
    fun applyNetworkQuality(packetLoss: Float) {
        val codec = encoder ?: return
        val lossPercent = (packetLoss * 100).toInt().coerceIn(0, 100)
        val fecEnabled = packetLoss >= FEC_ENABLE_LOSS
        val baseBitrate = audioConfig.bitrate.takeIf { it > 0 } ?: 32_000
        val target = when {
            packetLoss >= 0.15f -> baseBitrate / 2
            packetLoss >= 0.05f -> baseBitrate * 3 / 4
            else -> baseBitrate
        }.coerceAtLeast(MIN_ADAPTIVE_BITRATE)
        try {
            codec.setFec(fecEnabled)
            // Headroom above the observed loss so FEC covers bursts
            codec.setExpectedPacketLoss((lossPercent + 5).coerceAtMost(40))
            codec.setBitrate(target)
            Log.d(TAG, "Adaptive audio: loss=${"%.1f".format(packetLoss * 100)}% fec=$fecEnabled bitrate=$target")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to adapt encoder to network quality", e)
        }
    }

    private fun initAudioTrack() {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        audioTrack = AudioTrack.Builder()
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
            .setBufferSizeInBytes(maxOf(minBuf, FRAME_SIZE_BYTES * 2))
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
        audioTrack?.play()
    }

    /**
     * Playback loop: decodes one opus frame per user, mixes PCM, writes
     * one combined frame to AudioTrack. All decoder access is on this
     * single thread so no synchronization is needed on decoders.
     */
    private fun startPlaybackLoop() {
        playbackJob = playbackScope.launch {
            val mixBuffer = ShortArray(FRAME_SIZE_SAMPLES)
            val decodeBuffer = ShortArray(FRAME_SIZE_SAMPLES)

            while (isActive) {
                var hasData = false
                mixBuffer.fill(0)

                for ((userId, queue) in userQueues) {
                    val opusData = synchronized(queue) { queue.pollFirst() } ?: continue
                    val decoder = userDecoders.getOrPut(userId) { OpusCodec(audioConfig) }
                    try {
                        val pcmBytes = decoder.decode(opusData)
                        bytesToShorts(pcmBytes, decodeBuffer)
                        hasData = true
                        // Per-user gain (dB) applied before summing
                        val gainDb = userGainsDb[userId]
                        if (gainDb != null && gainDb != 0f) {
                            val g = VadGate.dbToGain(gainDb)
                            for (i in mixBuffer.indices) {
                                val sum = mixBuffer[i] + (decodeBuffer[i] * g).toInt()
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
                    } catch (_: Exception) {}
                }

                if (hasData) {
                    // Focus ducking multiplies the user gain — audio stays
                    // faintly audible under another app's short prompt
                    val gain = gainFactor * (if (duckedByFocus) DUCK_FACTOR else 1f)
                    if (gain != 1.0f) {
                        for (i in mixBuffer.indices) {
                            mixBuffer[i] = (mixBuffer[i] * gain).toInt()
                                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                                .toShort()
                        }
                    }
                    val bytes = shortsToBytes(mixBuffer)
                    audioTrack?.write(bytes, 0, bytes.size)
                } else {
                    // No audio data — block until a frame arrives (producer
                    // offers the wake signal) or 20ms elapses. Instant speech
                    // onset and zero wakeups during silence.
                    playbackWake.poll(20, java.util.concurrent.TimeUnit.MILLISECONDS)
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

    fun playAudio(userId: Int, opusData: ByteArray) {
        if (userId in mutedUserIds) return
        if (_isOutputMuted.value) return // Global output mute — discard incoming audio
        if (outputSuspendedByFocus) return // Another app holds focus — discard instead of queueing
        val queue = userQueues.getOrPut(userId) { ArrayDeque() }
        synchronized(queue) {
            if (queue.size < MAX_QUEUE_FRAMES) {
                queue.addLast(opusData)
            }
            // Drop oldest if queue is full (prevents unbounded lag)
        }
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
                synchronized(queue) { queue.clear() }
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
