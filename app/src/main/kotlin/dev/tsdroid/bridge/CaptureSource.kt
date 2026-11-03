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
    /** Platform voice processing — implies platform NS/AEC on most devices. */
    VOICE_COMMUNICATION("voice_communication", 7),

    /** Plain microphone, no platform voice pipeline. */
    MIC("mic", 1),

    /** Unprocessed capture; some devices sound clearer without the platform DSP. */
    VOICE_PERFORMANCE("voice_performance", 9);

    /** Usable source id on this device — VOICE_PERFORMANCE falls back below API 29. */
    fun resolveSourceId(sdkInt: Int = android.os.Build.VERSION.SDK_INT): Int =
        if (this == VOICE_PERFORMANCE && sdkInt < 29) VOICE_COMMUNICATION.sourceId else sourceId

    companion object {
        fun from(raw: String?): CaptureSource =
            entries.firstOrNull { it.raw == raw } ?: VOICE_COMMUNICATION
    }
}
