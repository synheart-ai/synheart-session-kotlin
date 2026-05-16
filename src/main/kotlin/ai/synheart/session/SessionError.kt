package ai.synheart.session

/** Session error sealed class following the EmotionError pattern. */
sealed class SessionError(message: String) : Exception(message) {
    abstract val code: SessionErrorCode

    class PermissionDenied(msg: String = "Permission denied") : SessionError(msg) {
        override val code = SessionErrorCode.PERMISSION_DENIED
    }

    class SensorUnavailable(msg: String = "Sensor unavailable") : SessionError(msg) {
        override val code = SessionErrorCode.SENSOR_UNAVAILABLE
    }

    class InvalidState(msg: String = "Invalid state") : SessionError(msg) {
        override val code = SessionErrorCode.INVALID_STATE
    }

    class LowBattery(msg: String = "Low battery") : SessionError(msg) {
        override val code = SessionErrorCode.LOW_BATTERY
    }

    class OsTerminated(msg: String = "OS terminated") : SessionError(msg) {
        override val code = SessionErrorCode.OS_TERMINATED
    }
}
