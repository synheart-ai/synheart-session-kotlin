package ai.synheart.session

/**
 * Typed status snapshot of an active session. Returned by
 * [SynheartSession.getStatus] — `null` when no session is active.
 *
 * Mirrors the Flutter / Swift sibling SDKs' `SessionStatus`.
 */
data class SessionStatus(
    val sessionId: String,
    val active: Boolean,
    val lastSeq: Int,
)
