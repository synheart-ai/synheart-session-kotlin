package ai.synheart.session

/**
 * Immutable snapshot of companion-watch connectivity (Wear OS).
 *
 * The wire shape is shared with the Flutter and Swift siblings:
 * `{ supported, reachable, paired, installed }`.
 */
data class WatchStatus(
    /**
     * The platform can talk to a watch at all.
     *
     * False when Google Play services is unavailable — a de-Googled ROM or an
     * emulator without Play. Distinct from [reachable]: unsupported means the
     * transport does not exist, not that no watch is connected.
     */
    val supported: Boolean = false,
    /** At least one Wear OS node is currently connected. */
    val reachable: Boolean = false,
    /**
     * A watch is paired.
     *
     * Wear OS does not expose pairing separately from connectivity, so this
     * tracks [reachable] on Android. It is carried for parity with iOS, where
     * `WCSession` reports the two independently.
     */
    val paired: Boolean = false,
    /**
     * The companion app is installed on the watch.
     *
     * As with [paired], Wear OS gives no separate signal, so it tracks
     * [reachable] on Android.
     */
    val installed: Boolean = false,
) {
    /** True when a session command would actually reach a watch. */
    val canStartSession: Boolean get() = supported && reachable

    fun toMap(): Map<String, Any> = mapOf(
        "supported" to supported,
        "reachable" to reachable,
        "paired" to paired,
        "installed" to installed,
    )

    companion object {
        /** Nothing connected, and the transport may not even exist. */
        val UNAVAILABLE = WatchStatus()

        fun fromMap(map: Map<String, Any?>): WatchStatus = WatchStatus(
            supported = map["supported"] as? Boolean ?: false,
            reachable = map["reachable"] as? Boolean ?: false,
            paired = map["paired"] as? Boolean ?: false,
            installed = map["installed"] as? Boolean ?: false,
        )
    }
}
