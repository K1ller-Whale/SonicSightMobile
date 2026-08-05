package com.k1llerwhale.sonicsight.data.model

/** Which frames the client sends for a model. */
enum class FrameKind {
    /** Two 224x224 crops, left/right halves (Sound of Pixels). */
    LEFT_RIGHT_HALVES,

    /** One full frame letterboxed to 224x224 (multisensory). */
    FULL_LETTERBOXED,
}

/**
 * Client mirror of the server's ModelSpec. Every value is the validated
 * constant for that model — not user-tunable. The id is sent as the
 * "sonicsight-model" gRPC metadata value; the server rejects unknown ids
 * with FAILED_PRECONDITION.
 *
 * Switching models is always: cancel the stream, reopen with new metadata.
 * The capture profiles are incompatible mid-stream.
 */
data class ModelProfile(
    val id: String,
    /** What the user picks by — what they are pointing the camera at. */
    val displayName: String,
    val frameIntervalMs: Long,
    /** 44100 / decimFactor = the wire rate. Exact integer factors only. */
    val decimFactor: Int,
    /** Anti-alias cutoff for the decimator, must be < 44100/(2*factor). */
    val decimCutoffHz: Double,
    /** PCM rate on the wire — capture upstream AND playback downstream. */
    val streamRate: Int,
    val frameKind: FrameKind,
    /** 2 = left/right maps, 1 = one full-frame map in left_heatmap. */
    val heatmapCount: Int,
    /** What the two audio streams MEAN. Never hard-code "Left/Right". */
    val streamLabels: Pair<String, String>,
    /** What the heatmap's high and low ends MEAN, for the legend. */
    val heatmapMeaning: Pair<String, String>,
    /** True: server may send an empty heatmap with low cam_confidence. */
    val confidenceGated: Boolean,
    /** Rough perceived lag, for honest buffering copy. */
    val expectedFirstResultMs: Long,
    /** Touch mode: energy map + on-demand region queries instead of fixed
     *  left/right tracks. Mirrors the server spec's mode="pixel". */
    val isPixel: Boolean = false,
) {
    companion object {
        /** The only rate Android guarantees on AudioRecord. */
        const val CAPTURE_RATE = 44100

        val SONICSIGHT = ModelProfile(
            id = "sonicsight",
            displayName = "Music & Instruments",
            frameIntervalMs = 125L,          // 8 fps
            decimFactor = 4,                 // 44100/4 = 11025 exactly
            decimCutoffHz = 5000.0,
            streamRate = 11025,
            frameKind = FrameKind.LEFT_RIGHT_HALVES,
            heatmapCount = 2,
            streamLabels = "Left" to "Right",
            heatmapMeaning = "Loud" to "Quiet",   // per-pixel sound energy
            confidenceGated = false,
            expectedFirstResultMs = 3000L,   // ~half the 5.9 s window
        )

        val MULTISENSORY = ModelProfile(
            id = "multisensory",
            displayName = "Speech",
            frameIntervalMs = 33L,           // ~30 fps
            decimFactor = 2,                 // 44100/2 = 22050 exactly
            decimCutoffHz = 10000.0,         // < Nyquist of the 22050 wire rate
            streamRate = 22050,
            frameKind = FrameKind.FULL_LETTERBOXED,
            heatmapCount = 1,
            streamLabels = "On-screen" to "Off-screen",
            heatmapMeaning = "Matches audio" to "No match",  // alignment strength
            confidenceGated = true,
            expectedFirstResultMs = 1200L,   // ~half the 2.1 s window
        )

        val SONICSIGHT_PIXEL = ModelProfile(
            id = "sonicsight-pixel",
            displayName = "Touch",
            frameIntervalMs = 125L,          // 8 fps, same cadence as halves
            decimFactor = 4,                 // 44100/4 = 11025 — SAME audio as halves;
                                             // full frames do NOT imply the hi rate
            decimCutoffHz = 5000.0,
            streamRate = 11025,
            frameKind = FrameKind.FULL_LETTERBOXED,
            heatmapCount = 0,                // energy map replaces legacy heatmaps
            streamLabels = "Selection" to "Everything else",
            heatmapMeaning = "Loud" to "Quiet",
            confidenceGated = false,
            expectedFirstResultMs = 3000L,   // same 5.9 s window as halves
            isPixel = true,
        )

        val DEFAULT = SONICSIGHT
        val ALL = listOf(SONICSIGHT, MULTISENSORY, SONICSIGHT_PIXEL)

        fun byId(id: String): ModelProfile = ALL.firstOrNull { it.id == id } ?: DEFAULT

        fun next(current: ModelProfile): ModelProfile =
            ALL[(ALL.indexOf(current) + 1) % ALL.size]
    }
}
