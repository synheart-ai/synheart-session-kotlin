package ai.synheart.session

/**
 * Sealed event hierarchy emitted by [SynheartSession.startSession].
 *
 * Stream lifecycle: `SessionStarted` → `SessionFrame*` → `SessionSummary`
 * (or terminating `SessionError`). When raw samples are requested,
 * `BiosignalFrame` events interleave between session frames.
 *
 * Mirrors the Flutter / Swift sibling SDKs' `SessionEvent`. The wire format
 * (returned by [toMap]) uses snake_case keys identical across all three.
 */
sealed class SessionEvent {
    abstract val sessionId: String
    abstract fun toMap(): Map<String, Any>

    companion object {
        /** Parse an event from a wire-format map. */
        @Suppress("UNCHECKED_CAST")
        fun fromMap(map: Map<String, Any>): SessionEvent {
            val type = map["type"] as? String
                ?: throw IllegalArgumentException("Missing type")
            val sessionId = map["session_id"] as? String
                ?: throw IllegalArgumentException("Missing session_id")
            return when (type) {
                "session_started" -> SessionStarted(
                    sessionId = sessionId,
                    startedAtMs = (map["started_at_ms"] as Number).toLong(),
                )
                "session_frame" -> SessionFrame(
                    sessionId = sessionId,
                    seq = (map["seq"] as Number).toInt(),
                    emittedAtMs = (map["emitted_at_ms"] as Number).toLong(),
                    metrics = map["metrics"] as Map<String, Any>,
                    behavior = map["behavior"] as? Map<String, Any?>,
                )
                "session_summary" -> SessionSummary(
                    sessionId = sessionId,
                    durationActualSec = (map["duration_actual_sec"] as Number).toInt(),
                    metrics = map["metrics"] as Map<String, Any>,
                    behavior = map["behavior"] as? Map<String, Any?>,
                )
                "session_error" -> SessionErrorEvent(
                    sessionId = sessionId,
                    code = SessionErrorCode.entries.firstOrNull {
                        it.value == map["error_code"]
                    } ?: SessionErrorCode.INVALID_STATE,
                    message = map["message"] as? String ?: "",
                )
                "biosignal_frame" -> BiosignalFrame(
                    sessionId = sessionId,
                    seq = (map["seq"] as Number).toInt(),
                    emittedAtMs = (map["emitted_at_ms"] as Number).toLong(),
                    samples = (map["samples"] as List<Map<String, Any>>),
                )
                else -> throw IllegalArgumentException("Unknown SessionEvent type: $type")
            }
        }
    }
}

/** Emitted exactly once at the start of a session. */
data class SessionStarted(
    override val sessionId: String,
    val startedAtMs: Long,
) : SessionEvent() {
    override fun toMap(): Map<String, Any> = mapOf(
        "type" to "session_started",
        "session_id" to sessionId,
        "started_at_ms" to startedAtMs,
    )
}

/** A periodic frame containing computed metrics over the active window. */
data class SessionFrame(
    override val sessionId: String,
    val seq: Int,
    val emittedAtMs: Long,
    val metrics: Map<String, Any>,
    val behavior: Map<String, Any?>? = null,
) : SessionEvent() {
    override fun toMap(): Map<String, Any> {
        val m = mutableMapOf<String, Any>(
            "type" to "session_frame",
            "session_id" to sessionId,
            "seq" to seq,
            "emitted_at_ms" to emittedAtMs,
            "metrics" to metrics,
        )
        behavior?.let { m["behavior"] = it }
        return m
    }
}

/** Emitted once when the session ends, either naturally or via [SynheartSession.stopSession]. */
data class SessionSummary(
    override val sessionId: String,
    val durationActualSec: Int,
    val metrics: Map<String, Any>,
    val behavior: Map<String, Any?>? = null,
) : SessionEvent() {
    override fun toMap(): Map<String, Any> {
        val m = mutableMapOf<String, Any>(
            "type" to "session_summary",
            "session_id" to sessionId,
            "duration_actual_sec" to durationActualSec,
            "metrics" to metrics,
        )
        behavior?.let { m["behavior"] = it }
        return m
    }
}

/** Emitted instead of summary when the session terminates abnormally. */
data class SessionErrorEvent(
    override val sessionId: String,
    val code: SessionErrorCode,
    val message: String,
) : SessionEvent() {
    override fun toMap(): Map<String, Any> = mapOf(
        "type" to "session_error",
        "session_id" to sessionId,
        "error_code" to code.value,
        "message" to message,
    )
}

/** Raw biosignal samples, emitted when [SessionConfig.includeRawSamples] is true. */
data class BiosignalFrame(
    override val sessionId: String,
    val seq: Int,
    val emittedAtMs: Long,
    val samples: List<Map<String, Any>>,
) : SessionEvent() {
    override fun toMap(): Map<String, Any> = mapOf(
        "type" to "biosignal_frame",
        "session_id" to sessionId,
        "seq" to seq,
        "emitted_at_ms" to emittedAtMs,
        "samples" to samples,
    )
}
