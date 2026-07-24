package rj.qmme.runtime

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import mqq.app.AppRuntime
import mqq.app.MobileQQ
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Single lifecycle/ownership view for the app's embedded MobileQQ runtime.
 *
 * The coordinator is intentionally observational in this phase: it does not
 * remove the existing MobileQQ reflection fallbacks or force a different
 * upstream lifecycle.  It gives every caller the same runtime identity and
 * generation so that stale caches can be diagnosed before stricter rejection
 * is enabled in a later phase.
 */
object RuntimeCoordinator {
    private const val TAG = "QMME-Runtime"

    private val generationCounter = AtomicLong(0L)
    private val currentSessionRef = AtomicReference<RuntimeSession?>(null)
    private val lifecycleState = MutableStateFlow(RuntimeLifecycleState.COLD)
    private val lifecycleLock = Any()

    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var lastProcessName: String? = null

    val state: StateFlow<RuntimeLifecycleState> = lifecycleState.asStateFlow()

    fun currentSession(): RuntimeSession? = currentSessionRef.get()

    fun currentRuntime(): AppRuntime? = currentSessionRef.get()?.runtime

    fun sessionFor(runtime: AppRuntime?): RuntimeSession? {
        if (runtime == null) return null
        return currentSessionRef.get()?.takeIf { it.runtime === runtime }
    }

    fun isCurrent(runtime: AppRuntime?): Boolean {
        if (runtime == null) return false
        return currentSessionRef.get()?.runtime === runtime
    }

    fun onApplicationAttach(
        context: Context,
        processName: String? = null,
        source: String = "Application.attachBaseContext",
    ) {
        applicationContext = context.applicationContext ?: context
        lastProcessName = processName ?: resolveProcessName(context)
        transition(RuntimeLifecycleState.ATTACHING)
        emit(
            event = "application_attach",
            source = source,
            processName = lastProcessName,
        )
    }

    fun onApplicationCreate(
        context: Context,
        processName: String? = null,
        source: String = "Application.onCreate",
    ) {
        applicationContext = context.applicationContext ?: context
        lastProcessName = processName ?: resolveProcessName(context)
        transition(RuntimeLifecycleState.APPLICATION_READY)
        emit(
            event = "application_create",
            source = source,
            processName = lastProcessName,
        )
    }

    /**
     * Register a newly constructed AppRuntime and allocate its generation.
     * Re-observing the same object is idempotent and does not allocate a new
     * generation.
     */
    fun registerRuntime(
        runtime: AppRuntime,
        processName: String? = null,
        source: String = "registerRuntime",
    ): RuntimeSession {
        synchronized(lifecycleLock) {
            val existing = currentSessionRef.get()
            if (existing?.runtime === runtime) {
                val observed = updateAccount(existing, runtime)
                currentSessionRef.set(observed)
                emit(
                    event = "runtime_register_duplicate",
                    runtime = runtime,
                    source = source,
                    processName = processName,
                    reason = "same runtime identity; generation retained",
                )
                return observed
            }

            val previousIdentity = existing?.runtimeIdentity
            transition(RuntimeLifecycleState.RUNTIME_CREATING)
            val session = RuntimeSession(
                generation = generationCounter.incrementAndGet(),
                runtime = runtime,
                processName = processName ?: lastProcessName ?: resolveProcessName(applicationContext),
                accountUin = readCurrentUin(runtime),
            )
            currentSessionRef.set(session)
            transition(RuntimeLifecycleState.RUNTIME_CREATED)
            emit(
                event = "runtime_registered",
                runtime = runtime,
                source = source,
                processName = session.processName,
                reason = "previousRuntimeIdentity=${previousIdentity ?: "none"}",
            )
            return session
        }
    }

    /**
     * Observe a runtime returned by MobileQQ.waitAppRuntime/peekAppRuntime or
     * another legacy path.  If the object changed, it is treated as a new
     * generation instead of silently replacing the current session.
     */
    fun observeRuntime(
        runtime: AppRuntime?,
        processName: String? = null,
        source: String = "observeRuntime",
    ): RuntimeSession? {
        if (runtime == null) {
            emit(
                event = "runtime_observe_null",
                source = source,
                processName = processName,
                reason = "caller returned null",
            )
            return null
        }

        val current = currentSessionRef.get()
        if (current == null || current.runtime !== runtime) {
            return registerRuntime(runtime, processName, source)
        }

        synchronized(lifecycleLock) {
            val refreshed = updateAccount(current, runtime)
            currentSessionRef.set(refreshed)
            val state = lifecycleState.value
            if (safeIsRunning(runtime) && state in setOf(
                    RuntimeLifecycleState.COLD,
                    RuntimeLifecycleState.ATTACHING,
                    RuntimeLifecycleState.APPLICATION_READY,
                    RuntimeLifecycleState.RUNTIME_CREATING,
                    RuntimeLifecycleState.RUNTIME_CREATED,
                    RuntimeLifecycleState.RUNTIME_STARTING,
                )
            ) {
                transition(RuntimeLifecycleState.RUNTIME_RUNNING)
            }
            emit(
                event = "runtime_observed",
                runtime = runtime,
                source = source,
                processName = processName ?: refreshed.processName,
            )
            return refreshed
        }
    }

    /**
     * Record the legacy sAppRuntime mirror and warn if it no longer points at
     * the coordinator's current object.
     */
    fun observeLegacyMirror(runtime: AppRuntime?, source: String = "legacyMirror") {
        val current = currentRuntime()
        if (runtime !== current) {
            emit(
                event = "legacy_mirror_mismatch",
                runtime = runtime,
                source = source,
                reason = "coordinatorRuntimeIdentity=${current?.let(::identity) ?: "none"}",
            )
        }
    }

    fun markRuntimeStarting(
        runtime: AppRuntime?,
        source: String = "markRuntimeStarting",
    ) {
        if (!isCurrent(runtime)) {
            emitStale("runtime_starting_stale", runtime, source)
            return
        }
        transition(RuntimeLifecycleState.RUNTIME_STARTING)
        emit("runtime_starting", runtime, source = source)
    }

    fun markAccountBinding(
        runtime: AppRuntime?,
        uin: String?,
        source: String = "markAccountBinding",
    ) {
        val session = ensureObserved(runtime, source) ?: return
        synchronized(lifecycleLock) {
            currentSessionRef.set(session.copy(accountUin = uin?.takeIf { it.isNotBlank() }))
            transition(RuntimeLifecycleState.ACCOUNT_BINDING)
            emit("account_binding", runtime, source = source, reason = "uin=${redactUin(uin)}")
        }
    }

    fun markAccountBound(
        runtime: AppRuntime?,
        uin: String?,
        source: String = "markAccountBound",
    ) {
        val session = ensureObserved(runtime, source) ?: return
        synchronized(lifecycleLock) {
            currentSessionRef.set(session.copy(accountUin = uin?.takeIf { it.isNotBlank() }))
            transition(RuntimeLifecycleState.ACCOUNT_BOUND)
            emit("account_bound", runtime, source = source, reason = "uin=${redactUin(uin)}")
        }
    }

    fun markKernelStarting(
        runtime: AppRuntime?,
        source: String = "markKernelStarting",
    ) {
        if (!isCurrent(runtime)) {
            emitStale("kernel_starting_stale", runtime, source)
            return
        }
        transition(RuntimeLifecycleState.KERNEL_STARTING)
        emit("kernel_starting", runtime, source = source)
    }

    fun markKernelReady(
        runtime: AppRuntime?,
        source: String = "markKernelReady",
    ) {
        if (!isCurrent(runtime)) {
            emitStale("kernel_ready_stale", runtime, source)
            return
        }
        transition(RuntimeLifecycleState.ONLINE)
        emit("kernel_ready", runtime, source = source)
    }

    fun markLogout(
        runtime: AppRuntime?,
        reason: String? = null,
        source: String = "markLogout",
    ) {
        if (runtime != null && !isCurrent(runtime)) {
            emitStale("logout_stale", runtime, source, reason)
            return
        }
        transition(RuntimeLifecycleState.LOGGING_OUT)
        emit("logout", runtime, source = source, reason = reason)
    }

    /**
     * Clear only the current session. A late callback from an old runtime must
     * never clear the newer runtime's ownership record.
     */
    fun clearRuntime(
        runtime: AppRuntime? = currentRuntime(),
        source: String = "clearRuntime",
    ) {
        synchronized(lifecycleLock) {
            val current = currentSessionRef.get()
            if (runtime != null && current?.runtime !== runtime) {
                emitStale("clear_runtime_stale", runtime, source)
                return
            }
            transition(RuntimeLifecycleState.RELEASING)
            emit("runtime_releasing", runtime, source = source)
            currentSessionRef.set(null)
            transition(RuntimeLifecycleState.STOPPED)
            emit("runtime_cleared", runtime, source = source)
        }
    }

    fun markFailed(
        runtime: AppRuntime?,
        source: String,
        reason: String?,
    ) {
        transition(RuntimeLifecycleState.FAILED)
        emit("lifecycle_failed", runtime, source = source, reason = reason)
    }

    /** Stable, non-PII-ish diagnostic representation used by caller logs. */
    fun redactUin(uin: String?): String {
        val value = uin?.trim().orEmpty()
        if (value.isEmpty()) return "none"
        return "len=${value.length},hash=${Integer.toHexString(value.hashCode())}"
    }

    private fun ensureObserved(runtime: AppRuntime?, source: String): RuntimeSession? {
        if (runtime == null) {
            emit("runtime_required_but_null", source = source, reason = "event ignored")
            return null
        }
        return if (isCurrent(runtime)) {
            currentSessionRef.get()
        } else {
            emitStale("runtime_not_current", runtime, source)
            null
        }
    }

    private fun updateAccount(session: RuntimeSession, runtime: AppRuntime): RuntimeSession {
        val accountUin = readCurrentUin(runtime)
        return if (accountUin.isNullOrBlank() || accountUin == session.accountUin) {
            session
        } else {
            session.copy(accountUin = accountUin)
        }
    }

    private fun emitStale(
        event: String,
        runtime: AppRuntime?,
        source: String,
        reason: String? = null,
    ) {
        emit(event, runtime = runtime, source = source, reason = reason)
    }

    private fun transition(next: RuntimeLifecycleState) {
        lifecycleState.value = next
    }

    private fun emit(
        event: String,
        runtime: AppRuntime? = null,
        source: String,
        processName: String? = null,
        reason: String? = null,
    ) {
        val current = currentSessionRef.get()
        val observedRuntime = runtime ?: current?.runtime
        val mobile = MobileQQ.sMobileQQ
        val actualPackage = applicationContext?.packageName ?: "unknown"
        val actualProcess = processName
            ?: lastProcessName
            ?: resolveProcessName(applicationContext)
            ?: "unknown"
        val mobileProcess = readMobileQQProcessName() ?: "unknown"
        val mobileReportedProcess = runCatching { mobile?.getQQProcessName() }.getOrNull() ?: "unknown"
        val currentRuntimeIdentity = current?.runtimeIdentity?.toString() ?: "none"
        val observedIdentity = observedRuntime?.let(::identity)?.toString() ?: "none"
        val account = readCurrentUin(observedRuntime) ?: current?.accountUin
        val fields = listOf(
            "event=$event",
            "actualPackage=$actualPackage",
            "processName=$actualProcess",
            "mobileQQProcessName=$mobileProcess",
            "mobileQQReportedProcessName=$mobileReportedProcess",
            "generation=${current?.generation ?: "none"}",
            "runtimeIdentity=$observedIdentity",
            "currentRuntimeIdentity=$currentRuntimeIdentity",
            "currentUin=${redactUin(account)}",
            "isLogin=${safeIsLogin(observedRuntime)}",
            "isRunning=${safeIsRunning(observedRuntime)}",
            "mobileQQRuntimeReady=${runCatching { mobile?.isRuntimeReady() }.getOrNull() ?: "unknown"}",
            "state=${lifecycleState.value}",
            "source=${safeField(source)}",
            "reason=${safeField(reason ?: "none")}",
        )
        Log.i(TAG, fields.joinToString(separator = " "))
    }

    private fun safeField(value: String): String =
        value.replace("\n", "_").replace("\r", "_").replace(" ", "_")

    private fun identity(runtime: AppRuntime): Int = System.identityHashCode(runtime)

    private fun readCurrentUin(runtime: AppRuntime?): String? =
        runCatching { runtime?.currentUin?.takeIf { it.isNotBlank() } }.getOrNull()

    private fun safeIsLogin(runtime: AppRuntime?): String =
        runCatching { runtime?.isLogin() }.getOrNull()?.toString() ?: "unknown"

    private fun safeIsRunning(runtime: AppRuntime?): Boolean =
        runCatching { runtime?.isRunning == true }.getOrDefault(false)

    private fun readMobileQQProcessName(): String? = runCatching {
        MobileQQ::class.java.getDeclaredField("processName").apply { isAccessible = true }
            .get(null) as? String
    }.getOrNull()

    private fun resolveProcessName(context: Context?): String? {
        if (context == null) return null
        val fromActivityThread = runCatching {
            val method: Method = Class.forName(
                "android.app.ActivityThread",
                false,
                Context::class.java.classLoader,
            ).getDeclaredMethod("currentProcessName")
            method.isAccessible = true
            method.invoke(null) as? String
        }.getOrNull()
        if (!fromActivityThread.isNullOrBlank()) return fromActivityThread

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { Application.getProcessName() }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        return runCatching {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            activityManager?.runningAppProcesses
                ?.firstOrNull { it.pid == Process.myPid() }
                ?.processName
        }.getOrNull()
    }

}
