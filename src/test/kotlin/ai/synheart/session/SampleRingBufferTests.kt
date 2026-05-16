package ai.synheart.session

import org.junit.Assert.*
import org.junit.Test

class SampleRingBufferTests {

    private fun makeSample(ts: Long, bpm: Double) =
        BiosignalSample(timestampMs = ts, bpm = bpm, source = "test")

    @Test
    fun testAppendAndReadBack() {
        val buffer = SampleRingBuffer(windowSec = 60)
        buffer.append(makeSample(1000, 72.0))
        buffer.append(makeSample(2000, 74.0))

        assertEquals(2, buffer.count())
        val pairs = buffer.asPairs()
        assertEquals(2, pairs.size)
        assertEquals(72.0, pairs[0].second, 0.01)
        assertEquals(74.0, pairs[1].second, 0.01)
    }

    @Test
    fun testEvictionRemovesOldSamples() {
        val buffer = SampleRingBuffer(windowSec = 5)
        for (i in 0 until 10) {
            buffer.append(makeSample(i * 1000L, 70.0 + i))
        }

        // Window is 5 seconds. Latest ts = 9000, cutoff = 4000.
        // Samples with ts < 4000 (0,1,2,3) evicted.
        val pairs = buffer.asPairs()
        assertEquals(6, pairs.size) // ts 4000..9000
        assertEquals(4000L, pairs.first().first)
        assertEquals(9000L, pairs.last().first)
    }

    @Test
    fun testAsPairsFormat() {
        val buffer = SampleRingBuffer(windowSec = 60)
        buffer.append(BiosignalSample(timestampMs = 5000, bpm = 80.0, rrIntervalsMs = listOf(750.0), source = "ble_hrm"))

        val pairs = buffer.asPairs()
        assertEquals(1, pairs.size)
        assertEquals(5000L, pairs[0].first)
        assertEquals(80.0, pairs[0].second, 0.01)
    }

    @Test
    fun testGetAllReturnsBiosignalSamples() {
        val buffer = SampleRingBuffer(windowSec = 60)
        buffer.append(BiosignalSample(timestampMs = 1000, bpm = 72.0, rrIntervalsMs = listOf(833.3), deviceId = "dev1", source = "ble_hrm"))

        val all = buffer.getAll()
        assertEquals(1, all.size)
        assertEquals("ble_hrm", all[0].source)
        assertEquals("dev1", all[0].deviceId)
        assertEquals(listOf(833.3), all[0].rrIntervalsMs)
    }

    @Test
    fun testEmptyBuffer() {
        val buffer = SampleRingBuffer(windowSec = 60)
        assertTrue(buffer.isEmpty())
        assertEquals(0, buffer.count())
        assertTrue(buffer.asPairs().isEmpty())
        assertTrue(buffer.getAll().isEmpty())
    }

    @Test
    fun testConcurrentAppendAndRead() {
        val buffer = SampleRingBuffer(windowSec = 60)

        val writer = Thread {
            for (i in 0 until 100) {
                buffer.append(makeSample(i * 100L, 72.0))
            }
        }

        val reader = Thread {
            for (i in 0 until 100) {
                buffer.asPairs()
                buffer.count()
            }
        }

        writer.start()
        reader.start()
        writer.join(5000)
        reader.join(5000)

        // No crash = thread safety holds.
        assertTrue(buffer.count() > 0)
    }
}
