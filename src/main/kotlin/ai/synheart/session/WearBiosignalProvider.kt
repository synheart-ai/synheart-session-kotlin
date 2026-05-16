package ai.synheart.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Biosignal provider that wraps a `BleHrmProvider` from synheart-wear.
 * Bridges the `SharedFlow<HeartRateSample>` into the callback-based
 * [BiosignalProvider] interface expected by [SessionEngine].
 *
 * Uses `compileOnly` dependency on synheart-wear — the provider parameter
 * is cast at runtime via `as?` so this class is safe to load even when
 * synheart-wear is absent from the classpath.
 */
class WearBiosignalProvider(provider: Any) : BiosignalProvider {

    // Runtime-cast to BleHrmProvider. Null if the wear library is not on the classpath.
    private val bleHrmProvider: Any? = provider

    override val isAvailable: Boolean
        get() {
            return try {
                val method = bleHrmProvider?.javaClass?.getMethod("isConnected")
                method?.invoke(bleHrmProvider) as? Boolean ?: false
            } catch (_: Exception) {
                false
            }
        }

    override val name: String = "ble_hrm"

    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @Suppress("UNCHECKED_CAST")
    override fun startStreaming(onSample: (BiosignalSample) -> Unit) {
        if (!isAvailable) {
            throw SessionError.SensorUnavailable("BLE HRM not connected")
        }

        // Access heartRateFlow via reflection so we don't need a compile-time import
        // when synheart-wear is a compileOnly dependency.
        val flowField = try {
            bleHrmProvider!!.javaClass.getMethod("getHeartRateFlow").invoke(bleHrmProvider)
        } catch (e: Exception) {
            throw SessionError.SensorUnavailable("Cannot access heartRateFlow: ${e.message}")
        }

        val heartRateFlow = flowField as? SharedFlow<*>
            ?: throw SessionError.SensorUnavailable("heartRateFlow is not a SharedFlow")

        job = scope.launch {
            heartRateFlow.collect { hrSample ->
                if (hrSample == null) return@collect
                // Extract fields reflectively from HeartRateSample
                val cls = hrSample.javaClass
                val tsMs = cls.getMethod("getTsMs").invoke(hrSample) as Long
                val bpm = (cls.getMethod("getBpm").invoke(hrSample) as Number).toDouble()
                val rrIntervalsMs = try {
                    cls.getMethod("getRrIntervalsMs").invoke(hrSample) as? List<Double>
                } catch (_: Exception) { null }
                val deviceId = try {
                    cls.getMethod("getDeviceId").invoke(hrSample) as? String
                } catch (_: Exception) { null }

                val sample = BiosignalSample(
                    timestampMs = tsMs,
                    bpm = bpm,
                    rrIntervalsMs = rrIntervalsMs,
                    deviceId = deviceId,
                    source = "ble_hrm"
                )
                onSample(sample)
            }
        }
    }

    override fun stopStreaming() {
        job?.cancel()
        job = null
    }
}
