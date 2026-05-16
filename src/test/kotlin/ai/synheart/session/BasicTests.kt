package ai.synheart.session

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class BasicTests {

    @Test
    fun testSessionModeValues() {
        assertEquals("focus", SessionMode.FOCUS.value)
        assertEquals("breathing", SessionMode.BREATHING.value)
        assertEquals(SessionMode.FOCUS, SessionMode.fromString("focus"))
        assertEquals(SessionMode.BREATHING, SessionMode.fromString("breathing"))
        assertNull(SessionMode.fromString("invalid"))
    }

    @Test
    fun testComputeProfileDefaults() {
        val profile = ComputeProfile()
        assertEquals(60, profile.windowSec)
        assertEquals(5, profile.emitIntervalSec)
    }

    @Test
    fun testComputeProfileFromMap() {
        val profile = ComputeProfile.fromMap(mapOf("window_sec" to 30, "emit_interval_sec" to 10))
        assertEquals(30, profile.windowSec)
        assertEquals(10, profile.emitIntervalSec)
    }

    @Test
    fun testSessionConfigFromMap() {
        val map = mapOf<String, Any>(
            "session_id" to "test-123",
            "mode" to "focus",
            "duration_sec" to 300,
            "profile" to mapOf("window_sec" to 60, "emit_interval_sec" to 5)
        )
        val config = SessionConfig.fromMap(map)
        assertEquals("test-123", config.sessionId)
        assertEquals(SessionMode.FOCUS, config.mode)
        assertEquals(300, config.durationSec)
    }

    @Test(expected = SessionError.InvalidState::class)
    fun testSessionConfigMissingFieldThrows() {
        val map = mapOf<String, Any>("mode" to "focus")
        SessionConfig.fromMap(map)
    }

    @Test
    fun testSessionErrorCodes() {
        val error = SessionError.PermissionDenied("test")
        assertEquals(SessionErrorCode.PERMISSION_DENIED, error.code)
        assertTrue(error.message!!.contains("test"))
    }

    @Test
    fun testComputeMetricsNonEmpty() {
        val samples = listOf(
            Pair(1000L, 72.0),
            Pair(2000L, 74.0),
            Pair(3000L, 70.0)
        )
        val metrics = SessionEngine.computeMetrics(samples)

        assertNotNull(metrics["hr_mean_bpm"])
        assertNotNull(metrics["hr_sdnn_ms"])
        assertNotNull(metrics["rmssd_ms"])
        assertEquals(3, metrics["sample_count"])
        assertEquals(1000L, metrics["start_ms"])
        assertEquals(3000L, metrics["end_ms"])
    }

    @Test
    fun testComputeMetricsEmpty() {
        val metrics = SessionEngine.computeMetrics(emptyList())

        assertEquals(0.0, metrics["hr_mean_bpm"])
        assertEquals(0.0, metrics["hr_sdnn_ms"])
        assertEquals(0.0, metrics["rmssd_ms"])
        assertEquals(0, metrics["sample_count"])
        assertEquals(0L, metrics["start_ms"])
        assertEquals(0L, metrics["end_ms"])
    }

    @Test
    fun testComputeMetricsWithHsiMetrics() {
        val samples = listOf(
            Pair(1000L, 72.0),
            Pair(2000L, 74.0),
            Pair(3000L, 70.0)
        )
        val hsiMetrics = mapOf<String, Any>(
            "hrv.sdnn_ms" to 42.5,
            "hrv.rmssd_ms" to 38.1,
            "hrv.pnn50" to 21.3
        )
        val metrics = SessionEngine.computeMetrics(samples, hsiMetrics)

        assertEquals(42.5, metrics["hr_sdnn_ms"])
        assertEquals(38.1, metrics["rmssd_ms"])
        assertEquals(21.3, metrics["pnn50"])
        assertNotNull(metrics["hr_mean_bpm"])
        assertEquals(3, metrics["sample_count"])
    }

    @Test
    fun testComputeMetricsWithNullHsiMetrics() {
        val samples = listOf(
            Pair(1000L, 72.0),
            Pair(2000L, 74.0)
        )
        val metrics = SessionEngine.computeMetrics(samples, null)

        assertEquals(0.0, metrics["hr_sdnn_ms"])
        assertEquals(0.0, metrics["rmssd_ms"])
        assertEquals(0.0, metrics["pnn50"])
    }

}

@RunWith(RobolectricTestRunner::class)
class EngineTests {

    @Test
    fun testIngestHsiMetricsStoresOnEngine() {
        val engine = SessionEngine()
        val hsiMetrics = mapOf<String, Any>(
            "hrv.sdnn_ms" to 50.0,
            "hrv.rmssd_ms" to 45.0,
            "hrv.pnn50" to 25.0
        )
        // Should not throw
        engine.ingestHsiMetrics(hsiMetrics)
    }

    @Test(expected = SessionError.InvalidState::class)
    fun testEngineRejectsDuplicate() {
        val engine = SessionEngine()
        val config = SessionConfig(
            sessionId = "dup-test",
            mode = SessionMode.FOCUS,
            durationSec = 60
        )

        engine.start(config) { }
        engine.start(config) { }
    }

    @Test
    fun testEngineGetStatusNilWhenIdle() {
        val engine = SessionEngine()
        assertNull(engine.getStatus())
    }

    @Test
    fun testEngineGetStatusActiveWhenRunning() {
        val engine = SessionEngine()
        val config = SessionConfig(
            sessionId = "status-test",
            mode = SessionMode.FOCUS,
            durationSec = 60
        )

        engine.start(config) { }

        val status = engine.getStatus()
        assertNotNull(status)
        assertEquals("status-test", status!!["session_id"])
        assertEquals(true, status["active"])

        engine.stop("status-test")
    }
}
