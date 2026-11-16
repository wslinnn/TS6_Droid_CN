package dev.tslib;

/**
 * Opus decoder only — no encoder is ever constructed.
 *
 * <p>Receive-side code needs one decoder per remote speaker and never encodes;
 * {@link OpusCodec} builds both halves, so a large channel used to carry an
 * idle encoder per talker (each with its own state block, each created on the
 * audio playback thread when that member joined).
 *
 * <p>Decoding semantics are identical to {@link OpusCodec}: same output length
 * ({@code frameSizeSamples × channels}), same FEC repair behaviour. Not
 * thread-safe.
 *
 * <pre>{@code
 * try (OpusDecoder decoder = new OpusDecoder(new AudioConfig())) {
 *     byte[] pcm = decoder.decode(packet);
 * }
 * }</pre>
 */
public class OpusDecoder implements AutoCloseable {
    private long nativePtr;

    /**
     * Create a decoder with default configuration.
     *
     * @throws TsLibException if creation fails
     */
    public OpusDecoder() {
        this(new AudioConfig());
    }

    /**
     * Create a decoder with the given configuration.
     *
     * @param config audio configuration
     * @throws TsLibException if creation fails
     */
    public OpusDecoder(AudioConfig config) {
        this.nativePtr = nativeCreate(config.sampleRate, config.channels,
                                      config.bitrate, config.frameSizeMs);
        if (this.nativePtr == 0) {
            throw new TsLibException("Failed to create OpusDecoder");
        }
    }

    /**
     * Decode an Opus packet to PCM (16-bit little-endian bytes).
     *
     * @param data Opus-encoded data
     * @return decoded PCM samples
     * @throws TsLibException on decoding error
     */
    public byte[] decode(byte[] data) {
        return decode(data, false);
    }

    /**
     * Decode an Opus packet, optionally using FEC decoding. With
     * {@code fec = true} this returns the loss-repair audio reconstructed from
     * the FEC data embedded in this packet — call it on the frame FOLLOWING a
     * lost one to conceal the loss.
     *
     * @param data Opus-encoded data
     * @param fec  true to decode the embedded FEC repair data instead of the
     *             packet's own audio
     * @return decoded PCM samples
     * @throws TsLibException on decoding error
     */
    public byte[] decode(byte[] data, boolean fec) {
        checkNotClosed();
        return nativeDecode(nativePtr, data, fec);
    }

    /**
     * Synthesize one frame of comfort audio for a lost packet (packet loss
     * concealment) — used when there is no successor frame to carry FEC data.
     *
     * @return decoded PCM samples
     * @throws TsLibException on error
     */
    public byte[] decodePlc() {
        checkNotClosed();
        return nativeDecodePlc(nativePtr);
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
            throw new IllegalStateException("OpusDecoder has been closed");
        }
    }

    // Native methods
    private static native long nativeCreate(int sampleRate, int channels,
                                             int bitrate, int frameSizeMs);
    private static native void nativeDestroy(long ptr);
    private static native byte[] nativeDecode(long ptr, byte[] data, boolean fec);
    private static native byte[] nativeDecodePlc(long ptr);
}
