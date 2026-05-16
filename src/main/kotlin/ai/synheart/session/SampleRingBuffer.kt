package ai.synheart.session

/** Thread-safe sliding window of [BiosignalSample] values.
 *  Evicts samples older than [windowSec] from the most recent sample on each append. */
class SampleRingBuffer(private val windowSec: Int) {

    private val samples = mutableListOf<BiosignalSample>()

    /** Append a sample and evict anything outside the window. */
    @Synchronized
    fun append(sample: BiosignalSample) {
        samples.add(sample)
        evict()
    }

    /** Returns (timestampMs, bpm) pairs matching the format `computeMetrics()` expects. */
    @Synchronized
    fun asPairs(): List<Pair<Long, Double>> =
        samples.map { Pair(it.timestampMs, it.bpm) }

    /** Returns all samples currently in the buffer (for `biosignal_frame`). */
    @Synchronized
    fun getAll(): List<BiosignalSample> = samples.toList()

    @Synchronized
    fun count(): Int = samples.size

    @Synchronized
    fun isEmpty(): Boolean = samples.isEmpty()

    private fun evict() {
        val latest = samples.lastOrNull() ?: return
        val cutoff = latest.timestampMs - windowSec * 1000L
        samples.removeAll { it.timestampMs < cutoff }
    }
}
