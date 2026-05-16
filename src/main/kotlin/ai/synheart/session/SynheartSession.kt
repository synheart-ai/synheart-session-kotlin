package ai.synheart.session

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.round

/**
 * Timer-driven session engine. Collects HR data from a [BiosignalProvider],
 * buffers samples in a sliding window, and emits typed [SessionEvent]s with
 * computed metrics on a [Flow].
 *
 * Mirrors the Flutter sibling `SynheartSession` and the Swift sibling
 * `SynheartSession`. The on-the-wire shape (returned by [SessionEvent.toMap])
 * is identical across all three SDKs.
 */
class SynheartSession(
    private val provider: BiosignalProvider = MockBiosignalProvider(),
    private val behaviorProvider: BehaviorProvider? = null,
) {

    private var config: SessionConfig? = null
    private var events: MutableSharedFlow<SessionEvent>? = null
    private val handler = Handler(Looper.getMainLooper())
    private var emitRunnable: Runnable? = null
    private var durationRunnable: Runnable? = null
    private var rawEmitRunnable: Runnable? = null
    private var startedAtMs: Long = 0
    private var seq: Int = 0
    private var rawSeq: Int = 0
    private var sampleBuffer: SampleRingBuffer? = null
    private var lastHsiMetrics: Map<String, Any>? = null
    private var disposed: Boolean = false

    /**
     * Ingest pre-computed HRV metrics from the runtime, scoped to a session.
     *
     * The session SDK does not compute HRV locally; the runtime supplies
     * artifact-filtered, authoritative values. No-op if [sessionId] does
     * not match the currently active session.
     */
    fun ingestHsiMetrics(sessionId: String, hsiMetrics: Map<String, Any>) {
        if (disposed) return
        if (config?.sessionId != sessionId) return
        lastHsiMetrics = hsiMetrics
    }

    /**
     * Start a new session and return a hot [SharedFlow] of [SessionEvent]s.
     *
     * Flow lifecycle: `SessionStarted` → `SessionFrame*` → `SessionSummary`
     * (or `SessionErrorEvent` on failure). When [SessionConfig.includeRawSamples]
     * is true, `BiosignalFrame` events interleave between session frames.
     *
     * Eagerly starts the biosignal provider and emission timers; callers
     * that subscribe late will miss earlier events (mirroring Flutter's
     * stream semantics).
     *
     * @throws SessionError.InvalidState if a session is already running or
     *   the instance has been disposed.
     */
    fun startSession(config: SessionConfig): SharedFlow<SessionEvent> {
        if (disposed) {
            throw SessionError.InvalidState("SynheartSession has been disposed")
        }
        this.config?.let {
            throw SessionError.InvalidState("Session ${it.sessionId} is already running")
        }

        val flow = MutableSharedFlow<SessionEvent>(
            // Replay the full session so late subscribers (and tests via
            // `replayCache`) see every event from `SessionStarted` onward.
            replay = 1024,
            extraBufferCapacity = 64,
        )
        this.config = config
        this.events = flow
        this.seq = 0
        this.rawSeq = 0
        this.startedAtMs = System.currentTimeMillis()
        this.sampleBuffer = SampleRingBuffer(config.profile.windowSec)

        emit(SessionStarted(
            sessionId = config.sessionId,
            startedAtMs = startedAtMs,
        ))

        // Start the biosignal provider. On error, emit session_error and bail.
        try {
            provider.startStreaming { sample ->
                sampleBuffer?.append(sample)
            }
        } catch (e: Exception) {
            emit(SessionErrorEvent(
                sessionId = config.sessionId,
                code = SessionErrorCode.SENSOR_UNAVAILABLE,
                message = e.message ?: "Provider error",
            ))
            resetState()
            return flow.asSharedFlow()
        }

        val intervalMs = config.profile.emitIntervalSec.toLong() * 1000
        emitRunnable = object : Runnable {
            override fun run() {
                emitFrame()
                handler.postDelayed(this, intervalMs)
            }
        }
        handler.postDelayed(emitRunnable!!, intervalMs)

        val durationMs = config.durationSec.toLong() * 1000
        durationRunnable = Runnable {
            doStop(config.sessionId)
        }
        handler.postDelayed(durationRunnable!!, durationMs)

        if (config.includeRawSamples) {
            val rawIntervalMs = (config.profile.rawEmitIntervalSec
                ?: config.profile.emitIntervalSec).toLong() * 1000
            rawEmitRunnable = object : Runnable {
                override fun run() {
                    emitBiosignalFrame()
                    handler.postDelayed(this, rawIntervalMs)
                }
            }
            handler.postDelayed(rawEmitRunnable!!, rawIntervalMs)
        }

        return flow.asSharedFlow()
    }

    /**
     * Stop a running session — emits a final [SessionSummary] and completes
     * the flow.
     *
     * @throws SessionError.InvalidState if no session is running or [sessionId]
     *   does not match the active session.
     */
    fun stopSession(sessionId: String) {
        if (disposed) return
        val cfg = config ?: throw SessionError.InvalidState("No active session")
        if (cfg.sessionId != sessionId) {
            throw SessionError.InvalidState(
                "Session ID mismatch: expected ${cfg.sessionId}, got $sessionId",
            )
        }
        doStop(sessionId)
    }

    /** Typed status snapshot of the current session, or `null` if none. */
    fun getStatus(): SessionStatus? {
        if (disposed) return null
        val cfg = config ?: return null
        return SessionStatus(
            sessionId = cfg.sessionId,
            active = true,
            lastSeq = seq,
        )
    }

    /** Release resources held by this instance. */
    fun dispose() {
        if (disposed) return
        disposed = true
        cancelTimers()
        try { provider.stopStreaming() } catch (_: Exception) {}
        events = null
        config = null
        sampleBuffer = null
        lastHsiMetrics = null
    }

    private fun emit(event: SessionEvent) {
        events?.tryEmit(event)
    }

    private fun resetState() {
        cancelTimers()
        config = null
        events = null
        sampleBuffer = null
        lastHsiMetrics = null
    }

    private fun cancelTimers() {
        emitRunnable?.let { handler.removeCallbacks(it) }
        emitRunnable = null
        durationRunnable?.let { handler.removeCallbacks(it) }
        durationRunnable = null
        rawEmitRunnable?.let { handler.removeCallbacks(it) }
        rawEmitRunnable = null
    }

    private fun emitFrame() {
        val cfg = config ?: return
        val buffer = sampleBuffer ?: return

        val samples = buffer.asPairs()
        if (samples.isEmpty()) return

        seq++
        val nowMs = System.currentTimeMillis()
        val metrics = computeMetrics(samples, lastHsiMetrics)
        val behavior = behaviorProvider?.takeIf { it.isAvailable }
            ?.currentSnapshot()
            ?.let { snapshotToMap(it) }

        emit(SessionFrame(
            sessionId = cfg.sessionId,
            seq = seq,
            emittedAtMs = nowMs,
            metrics = metrics,
            behavior = behavior,
        ))
    }

    private fun doStop(sessionId: String) {
        cancelTimers()
        try { provider.stopStreaming() } catch (_: Exception) {}

        val cfg = config ?: return
        val buffer = sampleBuffer ?: return

        val nowMs = System.currentTimeMillis()
        val durationActualSec = ((nowMs - startedAtMs) / 1000).toInt()
        val samples = buffer.asPairs()
        val metrics = computeMetrics(samples, lastHsiMetrics)
        val behavior = behaviorProvider?.takeIf { it.isAvailable }
            ?.currentSnapshot()
            ?.let { snapshotToMap(it) }

        emit(SessionSummary(
            sessionId = cfg.sessionId,
            durationActualSec = durationActualSec,
            metrics = metrics,
            behavior = behavior,
        ))

        resetState()
    }

    private fun emitBiosignalFrame() {
        val cfg = config ?: return
        val buffer = sampleBuffer ?: return

        val allSamples = buffer.getAll()
        if (allSamples.isEmpty()) return

        rawSeq++
        val nowMs = System.currentTimeMillis()
        val samples = allSamples.map { sample ->
            val dict = mutableMapOf<String, Any>(
                "timestamp_ms" to sample.timestampMs,
                "bpm" to sample.bpm,
                "source" to sample.source,
            )
            sample.rrIntervalsMs?.let { dict["rr_intervals_ms"] = it }
            sample.deviceId?.let { dict["device_id"] = it }
            dict.toMap()
        }

        emit(BiosignalFrame(
            sessionId = cfg.sessionId,
            seq = rawSeq,
            emittedAtMs = nowMs,
            samples = samples,
        ))
    }

    /** Convert a [BehaviorSnapshot] to a wire-format map with snake_case keys. */
    private fun snapshotToMap(snapshot: BehaviorSnapshot): Map<String, Any?> {
        return buildMap {
            snapshot.typingCadence?.let { put("typing_cadence", it) }
            snapshot.interKeyLatency?.let { put("inter_key_latency", it) }
            snapshot.burstLength?.let { put("burst_length", it) }
            snapshot.scrollVelocity?.let { put("scroll_velocity", it) }
            snapshot.scrollAcceleration?.let { put("scroll_acceleration", it) }
            snapshot.scrollJitter?.let { put("scroll_jitter", it) }
            snapshot.tapRate?.let { put("tap_rate", it) }
            put("app_switches_per_minute", snapshot.appSwitchesPerMinute)
            snapshot.foregroundDuration?.let { put("foreground_duration", it) }
            snapshot.idleGapSeconds?.let { put("idle_gap_seconds", it) }
            snapshot.stabilityIndex?.let { put("stability_index", it) }
            snapshot.fragmentationIndex?.let { put("fragmentation_index", it) }
            put("timestamp", snapshot.timestamp)
        }
    }

    companion object {
        /**
         * Compute metrics from timestamped BPM samples + ingested HRV from
         * the runtime. HRV metrics (SDNN, RMSSD, pNN50) come from the runtime,
         * which applies artifact filtering — the session SDK only computes
         * mean HR locally.
         */
        internal fun computeMetrics(
            samples: List<Pair<Long, Double>>,
            hsiMetrics: Map<String, Any>? = null,
        ): Map<String, Any> {
            if (samples.isEmpty()) {
                return mapOf(
                    "hr_mean_bpm" to 0.0,
                    "hr_sdnn_ms" to 0.0,
                    "rmssd_ms" to 0.0,
                    "pnn50" to 0.0,
                    "sample_count" to 0,
                    "start_ms" to 0L,
                    "end_ms" to 0L,
                )
            }

            val bpms = samples.map { it.second }
            val meanBpm = bpms.sum() / bpms.size
            val hsi = hsiMetrics ?: emptyMap()

            return mapOf(
                "hr_mean_bpm" to round(meanBpm * 10) / 10,
                "hr_sdnn_ms" to ((hsi["hrv.sdnn_ms"] as? Number)?.toDouble() ?: 0.0),
                "rmssd_ms" to ((hsi["hrv.rmssd_ms"] as? Number)?.toDouble() ?: 0.0),
                "pnn50" to ((hsi["hrv.pnn50"] as? Number)?.toDouble() ?: 0.0),
                "sample_count" to samples.size,
                "start_ms" to samples.first().first,
                "end_ms" to samples.last().first,
            )
        }
    }
}
