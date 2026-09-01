package ai.synheart.session

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the watch-session pieces extracted from `synheart-session-flutter`'s
 * Android plugin, where they were compiled into a Flutter plugin AAR that
 * nothing could depend on.
 *
 * The Data Layer itself needs a paired watch and cannot be exercised here, so
 * these pin the parts that are pure logic and were previously untested: the
 * event decode the relay feeds to [SessionEvent.fromMap], and the connectivity
 * snapshot's shape.
 */
@RunWith(RobolectricTestRunner::class)
class WatchSessionTests {

    // ── WatchStatus ──────────────────────────────────────────────────────

    @Test
    fun `unavailable status reports nothing supported`() {
        val status = WatchStatus.UNAVAILABLE
        assertFalse(status.supported)
        assertFalse(status.reachable)
        assertFalse(status.canStartSession)
    }

    @Test
    fun `supported without a connected node cannot start a session`() {
        // The distinction that matters: the transport exists, but no watch is
        // on the other end. Collapsing the two into one boolean loses the
        // difference between "this device can never do it" and "not right now".
        val status = WatchStatus(supported = true, reachable = false)
        assertTrue(status.supported)
        assertFalse(status.canStartSession)
    }

    @Test
    fun `status round-trips through its wire map`() {
        val original = WatchStatus(
            supported = true,
            reachable = true,
            paired = true,
            installed = true,
        )
        assertEquals(original, WatchStatus.fromMap(original.toMap()))
    }

    @Test
    fun `a partial status map defaults the missing keys to false`() {
        // The watch app is a separate binary on its own release cadence; a map
        // missing a key must not throw.
        val status = WatchStatus.fromMap(mapOf("supported" to true))
        assertTrue(status.supported)
        assertFalse(status.reachable)
    }

    // ── Event decode ─────────────────────────────────────────────────────

    @Test
    fun `a watch session frame decodes into a typed event`() {
        val json = JSONObject(
            """
            {
              "type": "session_frame",
              "session_id": "sess_1",
              "seq": 3,
              "emitted_at_ms": 1700000000000,
              "metrics": { "hr_mean_bpm": 62.5, "sample_count": 12 }
            }
            """.trimIndent(),
        )

        val event = SessionEvent.fromMap(WatchSessionRelay.jsonToMap(json))

        assertTrue(event is SessionFrame)
        val frame = event as SessionFrame
        assertEquals("sess_1", frame.sessionId)
        assertEquals(3, frame.seq)
        assertEquals(62.5, frame.metrics["hr_mean_bpm"] as Double, 0.001)
    }

    @Test
    fun `a nested metrics object survives the json conversion`() {
        // The relay hands SessionEvent.fromMap a Map, so nested JSONObjects must
        // become nested Maps — leaving them as JSONObject silently yields a
        // frame whose metrics cannot be read.
        val json = JSONObject("""{ "a": { "b": { "c": 1 } } }""")
        val map = WatchSessionRelay.jsonToMap(json)

        @Suppress("UNCHECKED_CAST")
        val inner = (map["a"] as Map<String, Any>)["b"] as Map<String, Any>
        assertEquals(1, inner["c"])
    }

    @Test
    fun `a json null is omitted rather than stored as a null value`() {
        // JSONObject.NULL is a sentinel object, not Kotlin's null. Storing it
        // verbatim would put a non-null placeholder where callers expect
        // absence, and `map["x"] != null` would be true for a missing field.
        val map = WatchSessionRelay.jsonToMap(JSONObject("""{ "present": 1, "absent": null }"""))
        assertEquals(1, map["present"])
        assertNull(map["absent"])
        assertFalse(map.containsKey("absent"))
    }

    @Test
    fun `an array of objects decodes to a list of maps`() {
        val json = JSONObject("""{ "samples": [ { "bpm": 60 }, { "bpm": 61 } ] }""")
        val map = WatchSessionRelay.jsonToMap(json)

        @Suppress("UNCHECKED_CAST")
        val samples = map["samples"] as List<Map<String, Any>>
        assertEquals(2, samples.size)
        assertEquals(61, samples[1]["bpm"])
    }

    // ── Message paths ────────────────────────────────────────────────────

    @Test
    fun `message paths match the companion watch app`() {
        // These strings are the contract with synheart-edge-watch-android, which
        // listens on the command path. Changing either silently stops every
        // watch session working, with no compile error on either side.
        assertEquals("/synheart/session/command", WatchSessionRelay.COMMAND_PATH)
        assertEquals("/synheart/session/event", WatchSessionRelay.EVENT_PATH)
    }
}
