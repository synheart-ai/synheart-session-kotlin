package ai.synheart.session

/** A single biosignal sample from any heart-rate source. */
data class BiosignalSample(
    val timestampMs: Long,
    val bpm: Double,
    val rrIntervalsMs: List<Double>? = null,
    val deviceId: String? = null,
    val source: String
)

/** Abstraction over any heart-rate data source (mock, BLE HRM, HealthKit, Health Connect). */
interface BiosignalProvider {
    val isAvailable: Boolean
    val name: String
    fun startStreaming(onSample: (BiosignalSample) -> Unit)
    fun stopStreaming()
}
