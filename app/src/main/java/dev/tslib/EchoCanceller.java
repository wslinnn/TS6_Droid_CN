package dev.tslib;

/**
 * Software acoustic echo canceller (speexdsp-based, runs entirely in-process).
 *
 * <p>Removes loudspeaker echo from the microphone signal by adaptively
 * modelling the acoustic path between the playback reference (the exact PCM
 * written to {@code AudioTrack}) and the capture. Feed one frame per call
 * from the capture thread only — the engine is stateful and not
 * thread-safe.
 *
 * <p>Call {@link #process} on every captured frame, including muted or
 * locally silent ones, so the adaptive filter stays converged; gate the
 * microphone at the encoder, not here. If a call throws, bypass the frame
 * (forward the raw mic samples) — an audible echo is better than swallowed
 * speech.
 *
 * <pre>{@code
 * try (EchoCanceller aec = new EchoCanceller(48000, 960, 12000)) {
 *     short[] mic = new short[960];  // raw capture frame
 *     short[] far = new short[960];  // playback mix frame, same timeline
 *     short[] out = new short[960];
 *     aec.process(mic, far, out);
 * }
 * }</pre>
 */
public final class EchoCanceller implements AutoCloseable {
    private long nativePtr;

    /**
     * Create an echo canceller.
     *
     * @param sampleRate    audio sample rate in Hz (48000)
     * @param frameSamples  samples per frame (960 = 20 ms @ 48 kHz)
     * @param tailSamples   filter tail in samples; echo paths slower than
     *                      this cannot be cancelled (12000 = 250 ms)
     * @throws TsLibException if creation fails
     */
    public EchoCanceller(int sampleRate, int frameSamples, int tailSamples) {
        this.nativePtr = nativeCreate(sampleRate, frameSamples, tailSamples);
        if (this.nativePtr == 0) {
            throw new TsLibException("Failed to create EchoCanceller");
        }
    }

    /**
     * Cancel the echo of {@code far} contained in {@code mic}, writing
     * {@code out}. All three arrays must be exactly one frame long.
     *
     * <p>Runs the full adaptive update — call once per frame on the capture
     * thread.
     *
     * @param mic raw microphone frame
     * @param far playback reference frame, aligned with the mic timeline
     * @param out receives the echo-cancelled frame
     * @return number of samples written to {@code out}
     * @throws TsLibException on engine error (caller must bypass the frame)
     */
    public int process(short[] mic, short[] far, short[] out) {
        checkNotClosed();
        return nativeProcess(nativePtr, mic, far, out);
    }

    /**
     * Discard the learned path estimate (e.g. after an audio route change
     * that breaks the reference timeline). Convergence restarts from
     * scratch.
     *
     * @throws TsLibException on error
     */
    public void reset() {
        checkNotClosed();
        nativeReset(nativePtr);
    }

    @Override
    public void close() {
        if (nativePtr != 0) {
            nativeDestroy(nativePtr);
            nativePtr = 0;
        }
    }

    private void checkNotClosed() {
        if (nativePtr == 0) {
            throw new IllegalStateException("EchoCanceller has been closed");
        }
    }

    // Native methods
    private static native long nativeCreate(int sampleRate, int frameSamples, int tailSamples);
    private static native void nativeDestroy(long ptr);
    private static native void nativeReset(long ptr);
    private static native int nativeProcess(long ptr, short[] mic, short[] far, short[] out);
}
