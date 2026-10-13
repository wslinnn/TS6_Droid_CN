package dev.tslib;

/**
 * Low-latency receiver for incoming voice frames.
 *
 * Registered via {@link Client#setAudioSink(AudioSink)}. Frames are invoked
 * synchronously on a native audio pump thread as they arrive — implementers
 * must return quickly and must not call back into the client.
 */
public interface AudioSink {
    /**
     * Called for every received voice frame.
     *
     * @param userId    sender's client id
     * @param seq       sender's per-packet sequence number (wraps at 2^16);
     *                  receivers use it to detect lost frames
     * @param data      encoded Opus frame
     * @param isWhisper true when the frame arrived as a whisper packet
     */
    void onAudioFrame(int userId, int seq, byte[] data, boolean isWhisper);
}
