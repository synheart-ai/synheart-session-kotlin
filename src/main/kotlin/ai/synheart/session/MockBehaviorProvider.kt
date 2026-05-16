package ai.synheart.session

/** Mock behavior provider that returns stable mid-range values for testing.
 *  Parallels [MockBiosignalProvider] for behavioral signals. */
class MockBehaviorProvider : BehaviorProvider {

    override val isAvailable: Boolean = true
    override val name: String = "mock"

    override fun currentSnapshot(): BehaviorSnapshot {
        return BehaviorSnapshot(
            typingCadence = 3.5,
            interKeyLatency = 120.0,
            burstLength = 8,
            scrollVelocity = 120.5,
            scrollAcceleration = 15.2,
            scrollJitter = 3.1,
            tapRate = 2.3,
            appSwitchesPerMinute = 4,
            foregroundDuration = 45.0,
            idleGapSeconds = 2.1,
            stabilityIndex = 0.82,
            fragmentationIndex = 0.15,
            timestamp = System.currentTimeMillis()
        )
    }
}
