package ai.synheart.session

/**
 * Typed status snapshot of an active session. Returned by
 * [SynheartSession.getStatus] — `null` when no session is active.
 *
 * Shared `SessionStatus` shape across the platform SDKs.
 */
data class SessionStatus(
    val sessionId: String,
    val active: Boolean,
    val lastSeq: Int,
)
