package ai.synheart.session

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class HealthConnectBiosignalProviderTests {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun testProviderNameIsCorrect() {
        val provider = HealthConnectBiosignalProvider(Object(), context)
        assertEquals("health_connect", provider.name)
    }

    @Test
    fun testIsAvailableReturnsFalseWithDummyAdapter() {
        // A plain Object has no Companion.isAvailable — reflection fails → false
        val provider = HealthConnectBiosignalProvider(Object(), context)
        assertFalse(provider.isAvailable)
    }

    @Test
    fun testStartStreamingThrowsWhenUnavailable() {
        val provider = HealthConnectBiosignalProvider(Object(), context)
        try {
            provider.startStreaming { }
            fail("Expected SensorUnavailable to be thrown")
        } catch (e: SessionError.SensorUnavailable) {
            // expected
        }
    }

    @Test
    fun testStopStreamingIsIdempotent() {
        val provider = HealthConnectBiosignalProvider(Object(), context)
        // Double stop should not crash
        provider.stopStreaming()
        provider.stopStreaming()
    }

    @Test
    fun testEngineHandlesHealthConnectProviderError() {
        val provider = HealthConnectBiosignalProvider(Object(), context)
        val engine = SessionEngine(provider)
        val config = SessionConfig(
            sessionId = "hc-error-test",
            mode = SessionMode.FOCUS,
            durationSec = 60
        )

        val events = mutableListOf<Map<String, Any>>()
        engine.start(config) { events.add(it) }

        // Engine should emit session_error because provider is unavailable
        val errors = events.filter { it["type"] == "session_error" }
        assertEquals(1, errors.size)
        assertEquals("sensor_unavailable", errors[0]["error_code"])

        // Engine should be idle
        assertNull(engine.getStatus())
    }
}
