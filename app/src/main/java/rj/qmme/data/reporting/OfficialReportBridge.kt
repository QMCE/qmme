package rj.qmme.data.reporting

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Lightweight reimplementation of the QMCE official-report bridge.
 *
 * The original also exposes a large reflective event-reporting API (page-in/out, element clicks,
 * chat-list exposure) tied to `RecentContactInfo` and Compose. This variant keeps only what
 * [QmmeApp][rj.qmme.QmmeApp] actually calls: it verifies whether QQ's own startup task
 * booted DT (`VideoReport`) and, if not, invokes that task reflectively. QIMEI/Beacon
 * startup is synchronous and process-scoped in `QmmeApp`; it must never be deferred here
 * because MSF can send its first SSO packet before this bridge's scheduled check.
 */
object OfficialReportBridge {
    private const val TAG = "QMME-OfficialReport"
    private const val INIT_DELAY_MILLIS = 3500L

    private const val VIDEO_REPORT_INNER =
        "com.tencent.qqlive.module.videoreport.inner.VideoReportInner"
    private const val DT_INIT_TASK = "com.tencent.qqnt.watch.startup.task.DtInitTask"

    enum class State { NOT_STARTED, SCHEDULED, INITIALIZED, UNAVAILABLE, FAILED }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)
    private val state = AtomicReference(State.NOT_STARTED)

    fun initialize(application: Application) {
        if (!started.compareAndSet(false, true)) return
        state.set(State.SCHEDULED)
        Log.d(TAG, "official reporter bridge scheduled")
        mainHandler.postDelayed({ verifyOrFallback(application) }, INIT_DELAY_MILLIS)
    }

    fun currentState(): State = state.get()

    private fun verifyOrFallback(application: Application) {
        if (isOfficialReporterInitialized()) {
            state.set(State.INITIALIZED)
        } else {
            state.set(kickInitTask(application, DT_INIT_TASK, "DtInitTask"))
        }
        Log.d(TAG, "official reporter state dt=${state.get()}")
    }

    private fun kickInitTask(application: Application, className: String, label: String): State {
        return runCatching {
            val taskClass = Class.forName(className)
            val task = taskClass.getDeclaredConstructor().newInstance()
            taskClass.getMethod("a", Context::class.java).invoke(task, application)
            Log.d(TAG, "official $label fallback invoked")
            State.INITIALIZED
        }.getOrElse { error ->
            val root = unwrap(error)
            Log.w(TAG, "official $label fallback failed", root)
            if (root is ClassNotFoundException || root is NoClassDefFoundError) {
                State.UNAVAILABLE
            } else {
                State.FAILED
            }
        }
    }

    private fun isOfficialReporterInitialized(): Boolean = runCatching {
        val innerClass = Class.forName(VIDEO_REPORT_INNER)
        val inner = innerClass.getMethod("getInstance").invoke(null)
        (innerClass.getMethod("isInit").invoke(inner) as? Boolean) == true
    }.getOrDefault(false)

    private fun unwrap(error: Throwable): Throwable =
        (error as? InvocationTargetException)?.targetException ?: error
    
    // Stub methods for TelemetryBridge compatibility
    fun report(key: String, value: String) {
        Log.v(TAG, "Telemetry stub: $key=$value")
    }
    
    fun flush() {
        Log.v(TAG, "Telemetry flushed (stub)")
    }
}
