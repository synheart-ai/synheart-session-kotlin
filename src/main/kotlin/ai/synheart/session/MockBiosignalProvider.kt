package ai.synheart.session

import android.os.Handler
import android.os.Looper
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Mock biosignal provider that generates sinusoidal HR data at 1 Hz.
 *  Extracts the same waveform previously embedded in SessionEngine:
 *  baseline 72 BPM, amplitude 5, cycle 4 s, noise ±2, clamped [40, 200]. */
class MockBiosignalProvider : BiosignalProvider {

    override val isAvailable: Boolean = true
    override val name: String = "mock"

    private val handler = Handler(Looper.getMainLooper())
    private var tickRunnable: Runnable? = null
    private var startTimeMs: Long = 0

    override fun startStreaming(onSample: (BiosignalSample) -> Unit) {
        startTimeMs = System.currentTimeMillis()
        // Emit an initial sample immediately so the buffer is never empty on the first frame.
        emitSample(onSample)
        tickRunnable = object : Runnable {
            override fun run() {
                emitSample(onSample)
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(tickRunnable!!, 1000)
    }

    override fun stopStreaming() {
        tickRunnable?.let { handler.removeCallbacks(it) }
        tickRunnable = null
    }

    private fun emitSample(onSample: (BiosignalSample) -> Unit) {
        val t = (System.currentTimeMillis() - startTimeMs) / 1000.0
        val baseline = 72.0
        val amplitude = 5.0
        val cycleSec = 4.0
        val sinComponent = amplitude * sin(2.0 * PI * t / cycleSec)
        val noise = Random.nextDouble(-2.0, 2.0)
        val bpm = min(200.0, max(40.0, baseline + sinComponent + noise))
        val rrMs = 60000.0 / bpm

        val sample = BiosignalSample(
            timestampMs = System.currentTimeMillis(),
            bpm = bpm,
            rrIntervalsMs = listOf(rrMs),
            source = "mock"
        )
        onSample(sample)
    }
}
