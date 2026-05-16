package ai.synheart.session

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Biosignal provider that wraps a `HealthConnectAdapter` from synheart-wear.
 * Bridges the adapter's `readSnapshot()` into the callback-based
 * [BiosignalProvider] interface expected by [SessionEngine].
 *
 * Uses `compileOnly`-safe reflection (same pattern as [WearBiosignalProvider])
 * so this class is safe to load even when synheart-wear is absent from the classpath.
 *
 * The caller is responsible for initializing the adapter (calling `initialize()`)
 * before passing it to this provider.
 *
 * @param adapter A `HealthConnectAdapter` instance from synheart-wear (typed as Any for reflection)
 * @param context Android application context for availability checks
 * @param pollIntervalMs Interval between Health Connect reads in milliseconds
 */
class HealthConnectBiosignalProvider(
    private val adapter: Any,
    private val context: Context,
    private val pollIntervalMs: Long = 2000L
) : BiosignalProvider {

    private val TAG = "HCBiosignalProvider"

    override val isAvailable: Boolean
        get() {
            return try {
                // Call HealthConnectAdapter.Companion.isAvailable(context) reflectively
                val companionField = adapter.javaClass.getDeclaredField("Companion")
                companionField.isAccessible = true
                val companion = companionField.get(null)
                val method = companion.javaClass.getMethod("isAvailable", Context::class.java)
                method.invoke(companion, context) as? Boolean ?: false
            } catch (e: Exception) {
                false
            }
        }

    override val name: String = "health_connect"

    private val handler = Handler(Looper.getMainLooper())
    private var pollRunnable: Runnable? = null
    private var scope: CoroutineScope? = null

    override fun startStreaming(onSample: (BiosignalSample) -> Unit) {
        if (!isAvailable) {
            throw SessionError.SensorUnavailable("Health Connect not available on this device")
        }

        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        // Poll immediately, then at interval
        pollAndEmit(onSample)
        pollRunnable = object : Runnable {
            override fun run() {
                pollAndEmit(onSample)
                handler.postDelayed(this, pollIntervalMs)
            }
        }
        handler.postDelayed(pollRunnable!!, pollIntervalMs)
    }

    override fun stopStreaming() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
        scope?.cancel()
        scope = null
    }

    private fun pollAndEmit(onSample: (BiosignalSample) -> Unit) {
        val currentScope = scope ?: return

        currentScope.launch(Dispatchers.IO) {
            try {
                val snapshot = callReadSnapshot(adapter) ?: return@launch

                // Extract fields from WearMetrics reflectively
                val timestamp = snapshot.javaClass.getMethod("getTimestamp").invoke(snapshot) as? Long
                    ?: System.currentTimeMillis()
                val deviceId = snapshot.javaClass.getMethod("getDeviceId").invoke(snapshot) as? String

                @Suppress("UNCHECKED_CAST")
                val metrics = snapshot.javaClass.getMethod("getMetrics").invoke(snapshot) as? Map<String, Double>
                    ?: return@launch

                val bpm = metrics["hr"] ?: return@launch

                val sample = BiosignalSample(
                    timestampMs = timestamp,
                    bpm = bpm,
                    rrIntervalsMs = listOf(60000.0 / bpm),
                    deviceId = deviceId,
                    source = "health_connect"
                )

                handler.post { onSample(sample) }
            } catch (e: Exception) {
                Log.w(TAG, "Poll error (will retry): ${e.message}")
            }
        }
    }

    /**
     * Call adapter.readSnapshot(isRealTime=true) reflectively as a suspend function.
     * Suspend functions compile to methods with an extra Continuation parameter.
     */
    private suspend fun callReadSnapshot(target: Any): Any? {
        return suspendCoroutine { cont ->
            try {
                val method = target.javaClass.methods.firstOrNull {
                    it.name == "readSnapshot" && it.parameterTypes.size == 2
                } ?: throw NoSuchMethodException("readSnapshot not found on adapter")

                val result = method.invoke(target, true, cont)
                if (result !== COROUTINE_SUSPENDED) {
                    cont.resume(result)
                }
                // If COROUTINE_SUSPENDED, the adapter will resume our continuation when done
            } catch (e: java.lang.reflect.InvocationTargetException) {
                cont.resumeWithException(e.targetException ?: e)
            } catch (e: Exception) {
                cont.resumeWithException(e)
            }
        }
    }
}
