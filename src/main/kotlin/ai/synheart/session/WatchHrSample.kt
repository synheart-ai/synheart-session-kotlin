package ai.synheart.session

/**
 * One heart-rate reading measured on the watch.
 *
 * Carries no derived values. The watch measures; whatever consumes this decides
 * what the number means — and in the Synheart stack that is the engine, not the
 * phone SDK.
 */
data class WatchHrSample(
    /** When the watch took the reading, in Unix ms. */
    val timestampMs: Long,
    val bpm: Double,
)
