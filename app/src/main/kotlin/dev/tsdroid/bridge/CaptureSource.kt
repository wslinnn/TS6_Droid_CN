package dev.tsdroid.bridge

/**
 * Microphone capture source.
 *
 * VOICE_PERFORMANCE is stored as the raw integer id (9): the MediaRecorder
 * constant is API 29+ and referencing it would raise NoClassDefFoundError on
 * older devices at class-load time (same approach as MicYou's AudioSource).
 */
enum class CaptureSource(
    val raw: String,
    val sourceId: Int,
) {
    /** Plain microphone, no contract on vendor processing. The raw-signal fallback. */
    MIC("mic", 1),

    /**
     * Platform voice pipeline (vendor NS/AEC/AGC chain). Last resort only:
     * its processing is nonlinear and device-dependent — on several ROMs it
     * hard-gates silence to exact zeros and it degrades the software AEC's
     * input, so the all-software audio pipeline avoids it by default.
     */
    VOICE_COMMUNICATION("voice_communication", 7),

    /**
     * Unprocessed low-latency capture — the default and the software DSP
     * pipeline's intended input: the signal reaches us before any vendor
     * voice processing, giving the software AEC a uniform, linear echo path
     * on every device.
     */
    VOICE_PERFORMANCE("voice_performance", 9);

    /** Usable source id on this device — VOICE_PERFORMANCE falls back to MIC below API 29. */
    fun resolveSourceId(sdkInt: Int = android.os.Build.VERSION.SDK_INT): Int =
        if (this == VOICE_PERFORMANCE && sdkInt < 29) MIC.sourceId else sourceId

    companion object {
        fun from(raw: String?): CaptureSource =
            entries.firstOrNull { it.raw == raw } ?: VOICE_PERFORMANCE
    }
}
