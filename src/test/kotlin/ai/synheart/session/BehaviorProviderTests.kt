package ai.synheart.session

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/** A test behavior provider that lets us control availability and snapshot. */
private class TestBehaviorProvider : BehaviorProvider {
    override var isAvailable: Boolean = true
    override val name: String = "test"

    var snapshot: BehaviorSnapshot? = BehaviorSnapshot(
        typingCadence = 3.5,
        interKeyLatency = 120.0,
        burstLength = 8,
        scrollVelocity = 120.5,
        scrollAcceleration = 15.2,
        scrollJitter = 3.1,
        tapRate = 2.3,
        appSwitchesPerMinute = 4,
        foregroundDuration = 45.0,
        idleGapSeconds = 2.1,
        stabilityIndex = 0.82,
        fragmentationIndex = 0.15,
        timestamp = 1708300000000L
    )

    override fun currentSnapshot(): BehaviorSnapshot? = snapshot
}

/** A test biosignal provider for injecting known samples. */
private class StubBiosignalProvider : BiosignalProvider {
    override var isAvailable: Boolean = true
    override val name: String = "test"

    private var onSample: ((BiosignalSample) -> Unit)? = null

    override fun startStreaming(onSample: (BiosignalSample) -> Unit) {
        if (!isAvailable) {
            throw SessionError.SensorUnavailable("Test provider unavailable")
        }
        this.onSample = onSample
    }

    override fun stopStreaming() {
        onSample = null
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

class MockBehaviorProviderTests {

    @Test
    fun testMockBehaviorProviderReturnsSnapshot() {
        val provider = MockBehaviorProvider()
        val snapshot = provider.currentSnapshot()
        assertNotNull(snapshot)
        assertEquals(3.5, snapshot!!.typingCadence!!, 0.01)
        assertEquals(120.0, snapshot.interKeyLatency!!, 0.01)
        assertEquals(8, snapshot.burstLength)
        assertEquals(120.5, snapshot.scrollVelocity!!, 0.01)
        assertEquals(15.2, snapshot.scrollAcceleration!!, 0.01)
        assertEquals(3.1, snapshot.scrollJitter!!, 0.01)
        assertEquals(2.3, snapshot.tapRate!!, 0.01)
        assertEquals(4, snapshot.appSwitchesPerMinute)
        assertEquals(45.0, snapshot.foregroundDuration!!, 0.01)
        assertEquals(2.1, snapshot.idleGapSeconds!!, 0.01)
        assertEquals(0.82, snapshot.stabilityIndex!!, 0.01)
        assertEquals(0.15, snapshot.fragmentationIndex!!, 0.01)
        assertTrue(snapshot.timestamp > 0)
    }

    @Test
    fun testMockBehaviorProviderIsAvailable() {
        val provider = MockBehaviorProvider()
        assertTrue(provider.isAvailable)
        assertEquals("mock", provider.name)
    }
}

@RunWith(RobolectricTestRunner::class)
class BehaviorEngineIntegrationTests {

    @Test
    fun testSnapshotToMap() {
        val behaviorProvider = TestBehaviorProvider()
        val biosignalProvider = StubBiosignalProvider()
        val engine = SynheartSession(biosignalProvider, behaviorProvider)

        val config = SessionConfig(
            sessionId = "snapshot-map-test",
            mode = SessionMode.FOCUS,
            durationSec = 60,
            profile = ComputeProfile(windowSec = 10, emitIntervalSec = 1)
        )

        val events = mutableListOf<Map<String, Any>>()
        val flow = engine.startSession(config)

        val baseTs = System.currentTimeMillis()
        biosignalProvider.emit(72.0, baseTs)

        ShadowLooper.idleMainLooper(1500, java.util.concurrent.TimeUnit.MILLISECONDS)

        engine.stopSession("snapshot-map-test")
        events.addAll(flow.replayCache.map { it.toMap() })

        val frames = events.filter { it["type"] == "session_frame" }
        assertTrue(frames.isNotEmpty())

        @Suppress("UNCHECKED_CAST")
        val behavior = frames.first()["behavior"] as? Map<String, Any?>
        assertNotNull(behavior)
        assertEquals(3.5, behavior!!["typing_cadence"] as Double, 0.01)
        assertEquals(120.0, behavior["inter_key_latency"] as Double, 0.01)
        assertEquals(8, behavior["burst_length"])
        assertEquals(120.5, behavior["scroll_velocity"] as Double, 0.01)
        assertEquals(15.2, behavior["scroll_acceleration"] as Double, 0.01)
        assertEquals(3.1, behavior["scroll_jitter"] as Double, 0.01)
        assertEquals(2.3, behavior["tap_rate"] as Double, 0.01)
        assertEquals(4, behavior["app_switches_per_minute"])
        assertEquals(45.0, behavior["foreground_duration"] as Double, 0.01)
        assertEquals(2.1, behavior["idle_gap_seconds"] as Double, 0.01)
        assertEquals(0.82, behavior["stability_index"] as Double, 0.01)
        assertEquals(0.15, behavior["fragmentation_index"] as Double, 0.01)
        assertEquals(1708300000000L, behavior["timestamp"])
    }

    @Test
    fun testEngineWithBehaviorIncludesBehaviorInFrame() {
        val behaviorProvider = TestBehaviorProvider()
        val biosignalProvider = StubBiosignalProvider()
        val engine = SynheartSession(biosignalProvider, behaviorProvider)

        val config = SessionConfig(
            sessionId = "behavior-frame-test",
            mode = SessionMode.FOCUS,
            durationSec = 60,
            profile = ComputeProfile(windowSec = 10, emitIntervalSec = 1)
        )

        val events = mutableListOf<Map<String, Any>>()
        val flow = engine.startSession(config)

        val baseTs = System.currentTimeMillis()
        biosignalProvider.emit(72.0, baseTs)
        biosignalProvider.emit(74.0, baseTs + 1000)

        ShadowLooper.idleMainLooper(1500, java.util.concurrent.TimeUnit.MILLISECONDS)

        engine.stopSession("behavior-frame-test")
        events.addAll(flow.replayCache.map { it.toMap() })

        val frames = events.filter { it["type"] == "session_frame" }
        assertTrue(frames.isNotEmpty())
        assertNotNull(frames.first()["behavior"])
    }

    @Test
    fun testEngineWithoutBehaviorOmitsBehaviorKey() {
        val biosignalProvider = StubBiosignalProvider()
        val engine = SynheartSession(biosignalProvider)

        val config = SessionConfig(
            sessionId = "no-behavior-test",
            mode = SessionMode.FOCUS,
            durationSec = 60,
            profile = ComputeProfile(windowSec = 10, emitIntervalSec = 1)
        )

        val events = mutableListOf<Map<String, Any>>()
        val flow = engine.startSession(config)

        val baseTs = System.currentTimeMillis()
        biosignalProvider.emit(72.0, baseTs)

        ShadowLooper.idleMainLooper(1500, java.util.concurrent.TimeUnit.MILLISECONDS)

        engine.stopSession("no-behavior-test")
        events.addAll(flow.replayCache.map { it.toMap() })

        val frames = events.filter { it["type"] == "session_frame" }
        assertTrue(frames.isNotEmpty())
        assertNull(frames.first()["behavior"])
    }

    @Test
    fun testEngineWithUnavailableBehaviorOmits() {
        val behaviorProvider = TestBehaviorProvider()
        behaviorProvider.isAvailable = false
        val biosignalProvider = StubBiosignalProvider()
        val engine = SynheartSession(biosignalProvider, behaviorProvider)

        val config = SessionConfig(
            sessionId = "unavail-behavior-test",
            mode = SessionMode.FOCUS,
            durationSec = 60,
            profile = ComputeProfile(windowSec = 10, emitIntervalSec = 1)
        )

        val events = mutableListOf<Map<String, Any>>()
        val flow = engine.startSession(config)

        val baseTs = System.currentTimeMillis()
        biosignalProvider.emit(72.0, baseTs)

        ShadowLooper.idleMainLooper(1500, java.util.concurrent.TimeUnit.MILLISECONDS)

        engine.stopSession("unavail-behavior-test")
        events.addAll(flow.replayCache.map { it.toMap() })

        val frames = events.filter { it["type"] == "session_frame" }
        assertTrue(frames.isNotEmpty())
        assertNull(frames.first()["behavior"])
    }

    @Test
    fun testSessionSummaryIncludesBehavior() {
        val behaviorProvider = TestBehaviorProvider()
        val biosignalProvider = StubBiosignalProvider()
        val engine = SynheartSession(biosignalProvider, behaviorProvider)

        val config = SessionConfig(
            sessionId = "summary-behavior-test",
            mode = SessionMode.FOCUS,
            durationSec = 60,
            profile = ComputeProfile(windowSec = 10, emitIntervalSec = 1)
        )

        val events = mutableListOf<Map<String, Any>>()
        val flow = engine.startSession(config)

        val baseTs = System.currentTimeMillis()
        biosignalProvider.emit(72.0, baseTs)

        engine.stopSession("summary-behavior-test")
        events.addAll(flow.replayCache.map { it.toMap() })

        val summaries = events.filter { it["type"] == "session_summary" }
        assertEquals(1, summaries.size)
        assertNotNull(summaries.first()["behavior"])

        @Suppress("UNCHECKED_CAST")
        val behavior = summaries.first()["behavior"] as? Map<String, Any?>
        assertEquals(0.82, behavior!!["stability_index"] as Double, 0.01)
    }

    @Test
    fun testBehaviorSdkProviderNameIsCorrect() {
        val provider = BehaviorSdkProvider(Object())
        assertEquals("synheart_behavior", provider.name)
    }

    @Test
    fun testBehaviorSdkProviderWithDummyReturnsNull() {
        // A plain Object has no getCurrentStats — reflection fails gracefully → null
        val provider = BehaviorSdkProvider(Object())
        assertFalse(provider.isAvailable)
        assertNull(provider.currentSnapshot())
    }
}
