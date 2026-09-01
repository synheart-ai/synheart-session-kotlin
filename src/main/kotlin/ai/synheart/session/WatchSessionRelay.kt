package ai.synheart.session

import android.content.Context
import android.util.Log
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject

/**
 * Phone-side relay that drives a session on a companion Wear OS watch over the
 * Wearable Data Layer.
 *
 *   1. [startSession] sends a `start_session` command to every connected node.
 *   2. The watch's engine streams events back on [EVENT_PATH].
 *   3. The returned [Flow] completes when the watch reports a terminal event
 *      (`session_summary` or `session_error`).
 *
 * The phone computes nothing here — the watch owns the session and this only
 * carries commands out and events in.
 *
 * ## Where this came from
 *
 * This logic previously lived only inside `synheart-session-flutter`'s Android
 * plugin, so it was compiled into a Flutter plugin AAR that nothing could depend
 * on — that module declares no `maven-publish`. A native Android host, and
 * `synheart-core-kotlin`, therefore had no way to run a watch session at all,
 * even though the implementation existed and worked. It belongs here, with the
 * rest of the session SDK, and the Flutter plugin should consume it rather than
 * carry its own copy.
 *
 * The callback API it had there is replaced with a [Flow]; the Data Layer
 * behaviour is otherwise unchanged, including the message paths, which the
 * companion watch app already listens on.
 */
class WatchSessionRelay(private val context: Context) {

    private val messageClient: MessageClient by lazy { Wearable.getMessageClient(context) }
    private val nodeClient by lazy { Wearable.getNodeClient(context) }

    @Volatile
    private var activeSession: String? = null

    /** The running watch session, or null. */
    val activeSessionId: String? get() = activeSession

    /** Whether a watch session is currently running. */
    val isActive: Boolean get() = activeSession != null

    // ── Status ───────────────────────────────────────────────────────────

    /**
     * Connectivity snapshot.
     *
     * Never throws: a Data Layer query failing is a disconnected watch, not an
     * error the caller can act on, so it reports [WatchStatus.reachable] false
     * while keeping `supported` honest about whether the transport exists.
     */
    suspend fun status(): WatchStatus {
        if (!isPlayServicesAvailable()) return WatchStatus.UNAVAILABLE
        val hasNode = runCatching { nodeClient.connectedNodes.await().isNotEmpty() }
            .onFailure { Log.w(TAG, "connectedNodes failed: ${it.message}") }
            .getOrDefault(false)
        // Wear OS reports no separate paired/installed signal — see WatchStatus.
        return WatchStatus(
            supported = true,
            reachable = hasNode,
            paired = hasNode,
            installed = hasNode,
        )
    }

    /** Whether at least one Wear OS node is connected. */
    suspend fun isReachable(): Boolean = status().reachable

    // ── Session ──────────────────────────────────────────────────────────

    /**
     * Start a session on the watch and stream its events.
     *
     * The flow registers the Data Layer listener before sending the command, so
     * an event cannot be missed between the two. It completes on the watch's
     * terminal event, and unregisters on completion or cancellation.
     *
     * Emits a [SessionErrorEvent] rather than throwing when no node is
     * connected: a watch going out of range is an ordinary runtime condition
     * that a caller renders, not an exception.
     */
    fun startSession(config: SessionConfig): Flow<SessionEvent> = callbackFlow {
        if (!isPlayServicesAvailable()) {
            trySend(
                SessionErrorEvent(
                    sessionId = config.sessionId,
                    code = SessionErrorCode.SENSOR_UNAVAILABLE,
                    message = "Google Play services unavailable; no watch transport",
                ),
            )
            close()
            return@callbackFlow
        }

        val listener = MessageClient.OnMessageReceivedListener { event ->
            if (event.path != EVENT_PATH) return@OnMessageReceivedListener
            val decoded = runCatching {
                SessionEvent.fromMap(jsonToMap(JSONObject(String(event.data, Charsets.UTF_8))))
            }.onFailure {
                Log.w(TAG, "could not decode watch event: ${it.message}")
            }.getOrNull() ?: return@OnMessageReceivedListener

            trySend(decoded)
            // The watch owns the session lifecycle; these are the two events
            // after which it sends nothing more.
            if (decoded is SessionSummary || decoded is SessionErrorEvent) {
                close()
            }
        }

        messageClient.addListener(listener)
        activeSession = config.sessionId

        val delivered = broadcast(startCommand(config))
        if (!delivered) {
            trySend(
                SessionErrorEvent(
                    sessionId = config.sessionId,
                    code = SessionErrorCode.SENSOR_UNAVAILABLE,
                    message = "No connected watch to start the session on",
                ),
            )
            close()
        }

        awaitClose {
            messageClient.removeListener(listener)
            activeSession = null
        }
    }

    /**
     * Ask the watch to stop a session.
     *
     * Best-effort and silent on failure: the watch also stops on its own
     * duration timer, so a dropped stop command ends the session late rather
     * than leaving it running forever.
     */
    suspend fun stopSession(sessionId: String) {
        broadcast(
            JSONObject().apply {
                put("command", "stop_session")
                put("session_id", sessionId)
            },
        )
        if (activeSession == sessionId) activeSession = null
    }

    // ── Internals ────────────────────────────────────────────────────────

    private fun startCommand(config: SessionConfig): JSONObject = JSONObject().apply {
        put("command", "start_session")
        put("session_id", config.sessionId)
        put("mode", config.mode.value)
        put("duration_sec", config.durationSec)
        put(
            "profile",
            JSONObject().apply {
                put("window_sec", config.profile.windowSec)
                put("emit_interval_sec", config.profile.emitIntervalSec)
            },
        )
        config.windowLabel?.let { put("window_label", it) }
    }

    /**
     * Send to every connected node.
     *
     * Returns whether any node accepted it. A watch can be connected but
     * transiently unable to receive, so one node failing must not abort the
     * others.
     */
    private suspend fun broadcast(message: JSONObject): Boolean {
        val payload = message.toString().toByteArray(Charsets.UTF_8)
        val nodes = runCatching { nodeClient.connectedNodes.await() }
            .onFailure { Log.w(TAG, "connectedNodes failed: ${it.message}") }
            .getOrDefault(emptyList())

        var anyDelivered = false
        for (node in nodes) {
            runCatching { messageClient.sendMessage(node.id, COMMAND_PATH, payload).await() }
                .onSuccess { anyDelivered = true }
                .onFailure { Log.w(TAG, "sendMessage to ${node.displayName} failed: ${it.message}") }
        }
        return anyDelivered
    }

    private fun isPlayServicesAvailable(): Boolean =
        GoogleApiAvailability.getInstance()
            .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

    internal companion object {
        private const val TAG = "WatchSessionRelay"

        /** Watch → phone. The companion app sends session events here. */
        const val EVENT_PATH = "/synheart/session/event"

        /** Phone → watch. The companion app listens here. */
        const val COMMAND_PATH = "/synheart/session/command"

        /** Recursive JSON → Map, so [SessionEvent.fromMap] can consume it. */
        internal fun jsonToMap(json: JSONObject): Map<String, Any> {
            val map = mutableMapOf<String, Any>()
            for (key in json.keys()) {
                when (val value = json.get(key)) {
                    is JSONObject -> map[key] = jsonToMap(value)
                    is JSONArray -> map[key] = jsonToList(value)
                    JSONObject.NULL -> Unit // absent, not null-valued
                    else -> map[key] = value
                }
            }
            return map
        }

        private fun jsonToList(array: JSONArray): List<Any?> =
            (0 until array.length()).map { i ->
                when (val value = array.get(i)) {
                    is JSONObject -> jsonToMap(value)
                    is JSONArray -> jsonToList(value)
                    JSONObject.NULL -> null
                    else -> value
                }
            }
    }
}
