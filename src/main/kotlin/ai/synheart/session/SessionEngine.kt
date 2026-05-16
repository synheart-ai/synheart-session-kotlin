package ai.synheart.session

import android.os.Handler
import android.os.Looper
import kotlin.math.round

/** Timer-driven session engine. Collects HR data from a [BiosignalProvider],
 *  buffers samples in a sliding window, and emits session frames with computed metrics. */
class SessionEngine(
    private val provider: BiosignalProvider = MockBiosignalProvider(),
    private val behaviorProvider: BehaviorProvider? = null
) {

    private var config: SessionConfig? = null
    private var callback: ((Map<String, Any>) -> Unit)? = null
    private val handler = Handler(Looper.getMainLooper())
    private var emitRunnable: Runnable? = null
    private var durationRunnable: Runnable? = null
    private var rawEmitRunnable: Runnable? = null
    private var startedAtMs: Long = 0
    private var seq: Int = 0
    private var rawSeq: Int = 0
    private var sampleBuffer: SampleRingBuffer? = null
    private var lastHsiMetrics: Map<String, Any>? = null

    /**
     * Ingest pre-computed HRV metrics from the runtime.
     * These are artifact-filtered and authoritative — the session SDK does not
     * compute HRV locally.
     */
    fun ingestHsiMetrics(hsiMetrics: Map<String, Any>) {
        lastHsiMetrics = hsiMetrics
    }

    /**
     * Start a new session.
     *
     * @param config Session configuration.
     * @param callback Called for each event map.
     * @throws SessionError.InvalidState if a session is already running.
     */
    fun start(config: SessionConfig, callback: (Map<String, Any>) -> Unit) {
        this.config?.let {
            throw SessionError.InvalidState("Session ${it.sessionId} is already running")
        }

        this.config = config
        this.callback = callback
        this.seq = 0
        this.rawSeq = 0
        this.startedAtMs = System.currentTimeMillis()
        this.sampleBuffer = SampleRingBuffer(config.profile.windowSec)

        callback(mapOf(
            "type" to "session_started",
            "session_id" to config.sessionId,
            "started_at_ms" to startedAtMs
        ))

        // Start the biosignal provider. On error, emit session_error and bail.
        try {
            provider.startStreaming { sample ->
                sampleBuffer?.append(sample)
            }
        } catch (e: Exception) {
            this.config = null
            this.callback = null
            this.sampleBuffer = null
            callback(mapOf(
                "type" to "session_error",
                "session_id" to config.sessionId,
                "error_code" to SessionErrorCode.SENSOR_UNAVAILABLE.value,
                "message" to (e.message ?: "Provider error")
            ))
            return
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
            config.sessionId.let { doStop(it) }
        }
        handler.postDelayed(durationRunnable!!, durationMs)

        if (config.includeRawSamples) {
            val rawIntervalMs = (config.profile.rawEmitIntervalSec ?: config.profile.emitIntervalSec).toLong() * 1000
            rawEmitRunnable = object : Runnable {
                override fun run() {
                    emitBiosignalFrame()
                    handler.postDelayed(this, rawIntervalMs)
                }
            }
            handler.postDelayed(rawEmitRunnable!!, rawIntervalMs)
        }
    }

    /**
     * Stop a running session.
     *
     * @param sessionId The session ID to stop.
     * @throws SessionError.InvalidState if no session is running or IDs don't match.
     */
    fun stop(sessionId: String) {
        val cfg = config ?: throw SessionError.InvalidState("No active session")
        if (cfg.sessionId != sessionId) {
            throw SessionError.InvalidState("Session ID mismatch: expected ${cfg.sessionId}, got $sessionId")
        }
        doStop(sessionId)
    }

    /**
     * Get the status of the current session.
     *
     * @return A status map or null if no session is active.
     */
    fun getStatus(): Map<String, Any>? {
        val cfg = config ?: return null
        return mapOf(
            "session_id" to cfg.sessionId,
            "active" to true,
            "last_seq" to seq
        )
    }

    private fun emitFrame() {
        val cfg = config ?: return
        val cb = callback ?: return
        val buffer = sampleBuffer ?: return

        val samples = buffer.asPairs()
        if (samples.isEmpty()) return

        seq++
        val nowMs = System.currentTimeMillis()
        val metrics = computeMetrics(samples, lastHsiMetrics)

        val frame = mutableMapOf<String, Any>(
            "type" to "session_frame",
            "session_id" to cfg.sessionId,
            "seq" to seq,
            "emitted_at_ms" to nowMs,
            "metrics" to metrics
        )

        behaviorProvider?.let { bp ->
            if (bp.isAvailable) {
                bp.currentSnapshot()?.let { snapshot ->
                    frame["behavior"] = snapshotToMap(snapshot)
                }
            }
        }

        cb(frame.toMap())
    }

    private fun doStop(sessionId: String) {
        emitRunnable?.let { handler.removeCallbacks(it) }
        emitRunnable = null
        durationRunnable?.let { handler.removeCallbacks(it) }
        durationRunnable = null
        rawEmitRunnable?.let { handler.removeCallbacks(it) }
        rawEmitRunnable = null

        provider.stopStreaming()

        val cfg = config ?: return
        val cb = callback ?: return
        val buffer = sampleBuffer ?: return

        val nowMs = System.currentTimeMillis()
        val durationActualSec = ((nowMs - startedAtMs) / 1000).toInt()

        val samples = buffer.asPairs()
        val metrics = computeMetrics(samples, lastHsiMetrics)

        val summary = mutableMapOf<String, Any>(
            "type" to "session_summary",
            "session_id" to cfg.sessionId,
            "duration_actual_sec" to durationActualSec,
            "metrics" to metrics
        )

        behaviorProvider?.let { bp ->
            if (bp.isAvailable) {
                bp.currentSnapshot()?.let { snapshot ->
                    summary["behavior"] = snapshotToMap(snapshot)
                }
            }
        }

        cb(summary.toMap())

        this.config = null
        this.callback = null
        this.sampleBuffer = null
        this.lastHsiMetrics = null
    }

    private fun emitBiosignalFrame() {
        val cfg = config ?: return
        val cb = callback ?: return
        val buffer = sampleBuffer ?: return

        val allSamples = buffer.getAll()
        if (allSamples.isEmpty()) return

        rawSeq++
        val nowMs = System.currentTimeMillis()

        cb(mapOf(
            "type" to "biosignal_frame",
            "session_id" to cfg.sessionId,
            "seq" to rawSeq,
            "emitted_at_ms" to nowMs,
            "samples" to allSamples.map { sample ->
                val dict = mutableMapOf<String, Any>(
                    "timestamp_ms" to sample.timestampMs,
                    "bpm" to sample.bpm,
                    "source" to sample.source
                )
                sample.rrIntervalsMs?.let { dict["rr_intervals_ms"] = it }
                sample.deviceId?.let { dict["device_id"] = it }
                dict.toMap()
            }
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
        /** Compute metrics from timestamped BPM samples + ingested HRV from the session runtime.
         *  HRV metrics (SDNN, RMSSD, pNN50) come from the runtime, which applies
         *  artifact filtering — the session SDK only computes mean HR locally. */
        internal fun computeMetrics(
            samples: List<Pair<Long, Double>>,
            hsiMetrics: Map<String, Any>? = null
        ): Map<String, Any> {
            if (samples.isEmpty()) {
                return mapOf(
                    "hr_mean_bpm" to 0.0,
                    "hr_sdnn_ms" to 0.0,
                    "rmssd_ms" to 0.0,
                    "pnn50" to 0.0,
                    "sample_count" to 0,
                    "start_ms" to 0L,
                    "end_ms" to 0L
                )
            }

            val bpms = samples.map { it.second }
            val meanBpm = bpms.sum() / bpms.size

            // HRV metrics come from the runtime (artifact-filtered, authoritative)
            val hsi = hsiMetrics ?: emptyMap()

            return mapOf(
                "hr_mean_bpm" to round(meanBpm * 10) / 10,
                "hr_sdnn_ms" to ((hsi["hrv.sdnn_ms"] as? Number)?.toDouble() ?: 0.0),
                "rmssd_ms" to ((hsi["hrv.rmssd_ms"] as? Number)?.toDouble() ?: 0.0),
                "pnn50" to ((hsi["hrv.pnn50"] as? Number)?.toDouble() ?: 0.0),
                "sample_count" to samples.size,
                "start_ms" to samples.first().first,
                "end_ms" to samples.last().first
            )
        }
    }
}
