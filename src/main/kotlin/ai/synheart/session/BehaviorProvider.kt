package ai.synheart.session

/**
 * A point-in-time snapshot of behavioral signals.
 * Mirrors `BehaviorStats` from synheart-behavior-kotlin field-for-field
 * but lives in synheart-session to avoid an import dependency.
 */
data class BehaviorSnapshot(
    /** Current typing cadence (keys per second). */
    val typingCadence: Double? = null,
    /** Current inter-key latency in milliseconds. */
    val interKeyLatency: Double? = null,
    /** Current burst length (number of keys in current burst). */
    val burstLength: Int? = null,
    /** Current scroll velocity (pixels per second). */
    val scrollVelocity: Double? = null,
    /** Current scroll acceleration (pixels per second squared). */
    val scrollAcceleration: Double? = null,
    /** Current scroll jitter (variance in scroll speed). */
    val scrollJitter: Double? = null,
    /** Current tap rate (taps per second). */
    val tapRate: Double? = null,
    /** Number of app switches in the last minute. */
    val appSwitchesPerMinute: Int = 0,
    /** Current foreground duration in seconds. */
    val foregroundDuration: Double? = null,
    /** Current idle gap duration in seconds. */
    val idleGapSeconds: Double? = null,
    /** Current session stability index (0.0 to 1.0). */
    val stabilityIndex: Double? = null,
    /** Current fragmentation index (0.0 to 1.0). */
    val fragmentationIndex: Double? = null,
    /** Timestamp when these stats were captured. */
    val timestamp: Long
)

/**
 * Abstraction over any behavioral signal source (mock, synheart-behavior SDK).
 * Pull-based — [SessionEngine] queries [currentSnapshot] at each frame tick.
 */
interface BehaviorProvider {
    val isAvailable: Boolean
    val name: String
    fun currentSnapshot(): BehaviorSnapshot?
}
