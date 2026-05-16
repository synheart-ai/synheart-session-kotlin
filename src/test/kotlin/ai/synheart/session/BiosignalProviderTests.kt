package ai.synheart.session

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/** A test provider that lets us inject samples on demand. */
private class TestBiosignalProvider : BiosignalProvider {
    override var isAvailable: Boolean = true
    override val name: String = "test"

    private var onSample: ((BiosignalSample) -> Unit)? = null
    var streaming: Boolean = false
        private set

    override fun startStreaming(onSample: (BiosignalSample) -> Unit) {
        if (!isAvailable) {
            throw SessionError.SensorUnavailable("Test provider unavailable")
        }
        this.onSample = onSample
        streaming = true
    }

    override fun stopStreaming() {
        onSample = null
        streaming = false
    }

    fun emit(bpm: Double, ts: Long = System.currentTimeMillis()) {
        val sample = BiosignalSample(
            timestampMs = ts,
            bpm = bpm,
            rrIntervalsMs = listOf(60000.0 / bpm),
            source = "test"
        )
        onSample?.invoke(sample)
    }
}

@RunWith(RobolectricTestRunner::class)
class BiosignalProviderTests {

    @Test
    fun testMockProviderEmitsSamples() {
        val provider = MockBiosignalProvider()
        assertTrue(provider.isAvailable)
        assertEquals("mock", provider.name)

        val received = mutableListOf<BiosignalSample>()
        provider.startStreaming { received.add(it) }

        // Initial sample emitted immediately
        assertEquals(1, received.size)
        assertEquals("mock", received[0].source)
        assertTrue(received[0].bpm in 40.0..200.0)

        // Advance by 2 seconds for 2 more samples
        ShadowLooper.idleMainLooper(2000, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertTrue(received.size >= 3)

        provider.stopStreaming()
    }

    @Test
    fun testTestProviderInjectsKnownSamples() {
        val provider = TestBiosignalProvider()
        val received = mutableListOf<BiosignalSample>()

        provider.startStreaming { received.add(it) }

        provider.emit(72.0, 1000)
        provider.emit(80.0, 2000)

        assertEquals(2, received.size)
        assertEquals(72.0, received[0].bpm, 0.01)
        assertEquals(80.0, received[1].bpm, 0.01)
        assertEquals("test", received[0].source)

        provider.stopStreaming()
    }

    @Test
    fun testEngineWithTestProviderReceivesSamplesInBuffer() {
        val provider = TestBiosignalProvider()
        val engine = SessionEngine(provider)
        val config = SessionConfig(
            sessionId = "provider-test",
            mode = SessionMode.FOCUS,
            durationSec = 60,
            profile = ComputeProfile(windowSec = 10, emitIntervalSec = 1)
        )

        val events = mutableListOf<Map<String, Any>>()
        engine.start(config) { events.add(it) }

        assertTrue(provider.streaming)

        // Inject known samples
        val baseTs = System.currentTimeMillis()
        provider.emit(72.0, baseTs)
        provider.emit(74.0, baseTs + 1000)
        provider.emit(76.0, baseTs + 2000)

        // Advance looper to trigger frame emission
        ShadowLooper.idleMainLooper(1500, java.util.concurrent.TimeUnit.MILLISECONDS)

        engine.stop("provider-test")
        assertFalse(provider.streaming)

        assertEquals("session_started", events.first()["type"])

        val summaries = events.filter { it["type"] == "session_summary" }
        assertEquals(1, summaries.size)

        @Suppress("UNCHECKED_CAST")
        val metrics = summaries[0]["metrics"] as? Map<String, Any>
        assertNotNull(metrics)
        assertEquals(3, metrics!!["sample_count"])
    }

    @Test
    fun testEngineEmitsSessionErrorOnProviderFailure() {
        val provider = TestBiosignalProvider()
        provider.isAvailable = false
        val engine = SessionEngine(provider)
        val config = SessionConfig(
            sessionId = "error-test",
            mode = SessionMode.FOCUS,
            durationSec = 60
        )

        val events = mutableListOf<Map<String, Any>>()
        engine.start(config) { events.add(it) }

        val errors = events.filter { it["type"] == "session_error" }
        assertEquals(1, errors.size)
        assertEquals("sensor_unavailable", errors[0]["error_code"])

        // Engine should be idle
        assertNull(engine.getStatus())
    }

    @Test
    fun testDefaultInitUsesMockProvider() {
        val engine = SessionEngine()
        val config = SessionConfig(
            sessionId = "default-test",
            mode = SessionMode.FOCUS,
            durationSec = 60,
            profile = ComputeProfile(windowSec = 5, emitIntervalSec = 1)
        )

        val events = mutableListOf<Map<String, Any>>()
        engine.start(config) { events.add(it) }

        // Advance looper for a frame
        ShadowLooper.idleMainLooper(1500, java.util.concurrent.TimeUnit.MILLISECONDS)

        engine.stop("default-test")

        val frames = events.filter { it["type"] == "session_frame" }
        assertTrue(frames.isNotEmpty())
    }
}
