package ai.synheart.session

/**
 * Wraps `SynheartBehavior.getCurrentStats()` from synheart-behavior-kotlin
 * into the [BehaviorProvider] interface expected by [SessionEngine].
 *
 * Uses reflection so synheart-behavior is only needed at runtime — not at
 * compile time. Follows the same pattern as [WearBiosignalProvider].
 */
class BehaviorSdkProvider(private val sdk: Any) : BehaviorProvider {

    override val isAvailable: Boolean
        get() {
            return try {
                val method = sdk.javaClass.getMethod("isInitialized")
                method.invoke(sdk) as? Boolean ?: false
            } catch (_: Exception) {
                false
            }
        }

    override val name: String = "synheart_behavior"

    override fun currentSnapshot(): BehaviorSnapshot? {
        return try {
            val statsObj = sdk.javaClass.getMethod("getCurrentStats").invoke(sdk) ?: return null
            val cls = statsObj.javaClass

            BehaviorSnapshot(
                typingCadence = getDoubleField(cls, statsObj, "getTypingCadence"),
                interKeyLatency = getDoubleField(cls, statsObj, "getInterKeyLatency"),
                burstLength = getIntField(cls, statsObj, "getBurstLength"),
                scrollVelocity = getDoubleField(cls, statsObj, "getScrollVelocity"),
                scrollAcceleration = getDoubleField(cls, statsObj, "getScrollAcceleration"),
                scrollJitter = getDoubleField(cls, statsObj, "getScrollJitter"),
                tapRate = getDoubleField(cls, statsObj, "getTapRate"),
                appSwitchesPerMinute = try {
                    cls.getMethod("getAppSwitchesPerMinute").invoke(statsObj) as? Int ?: 0
                } catch (_: Exception) { 0 },
                foregroundDuration = getDoubleField(cls, statsObj, "getForegroundDuration"),
                idleGapSeconds = getDoubleField(cls, statsObj, "getIdleGapSeconds"),
                stabilityIndex = getDoubleField(cls, statsObj, "getStabilityIndex"),
                fragmentationIndex = getDoubleField(cls, statsObj, "getFragmentationIndex"),
                timestamp = try {
                    cls.getMethod("getTimestamp").invoke(statsObj) as? Long ?: 0L
                } catch (_: Exception) { 0L }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun getDoubleField(cls: Class<*>, obj: Any, getter: String): Double? {
        return try {
            cls.getMethod(getter).invoke(obj) as? Double
        } catch (_: Exception) {
            null
        }
    }

    private fun getIntField(cls: Class<*>, obj: Any, getter: String): Int? {
        return try {
            cls.getMethod(getter).invoke(obj) as? Int
        } catch (_: Exception) {
            null
        }
    }
}
