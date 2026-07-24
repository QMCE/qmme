package rj.qmme

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.app.ActivityOptions
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.multidex.MultiDex
import com.tencent.mmkv.MMKV
import com.tencent.mobileqq.qmmkv.MMKVHandlerImpl
import com.tencent.mobileqq.qmmkv.QMMKV
import com.tencent.qqnt.watch.app.WatchAppInterface
import com.tencent.qqnt.watch.app.WatchApplicationDelegate
import com.tencent.qphone.base.remote.SimpleAccount
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import mqq.app.AppRuntime
import mqq.app.Constants
import mqq.app.IAccountCallback
import mqq.app.MobileQQ
import rj.qmme.data.LoginPrefs
import rj.qmme.data.emotion.EmotionAssetBridge
import rj.qmme.data.reporting.OfficialReportBridge
import rj.qmme.diagnostics.OfflineDiagnostics
import rj.qmme.fix.LegacyKiller
import rj.qmme.fix.PackageSignatureProvider
import rj.qmme.fix.SignatureProbe
import rj.qmme.kernel.KernelBridge
import rj.qmme.runtime.RuntimeCoordinator
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess


@Suppress("SpellCheckingInspection")
class QmmeApp : WatchApplicationDelegate() {
    private val logoutCallback = object : IAccountCallback {
        override fun onAccountChangeFailed(runtime: AppRuntime?) {
            OfflineDiagnostics.record(
                this@QmmeApp,
                "account_change_failed",
                "runtimeIdentity=${runtime?.let(System::identityHashCode) ?: "none"} " +
                        "isLogin=${runCatching { runtime?.isLogin() }.getOrNull()}",
            )
        }

        override fun onAccountChanged(runtime: AppRuntime?) {
            OfflineDiagnostics.record(
                this@QmmeApp,
                "account_changed",
                "runtimeIdentity=${runtime?.let(System::identityHashCode) ?: "none"} " +
                        "isLogin=${runCatching { runtime?.isLogin() }.getOrNull()}",
            )
        }

        override fun onLogout(reason: Constants.LogoutReason?) {
            val active = RuntimeCoordinator.currentRuntime()
            OfflineDiagnostics.record(
                this@QmmeApp,
                "account_logout_callback",
                "reason=${reason ?: "unknown"} forced=${reason in forcedLogoutReasons} " +
                        "runtimeIdentity=${active?.let(System::identityHashCode) ?: "none"} " +
                        "isLogin=${runCatching { active?.isLogin() }.getOrNull()} " +
                        "hasPersistedAccount=${LoginPrefs.hasAccount(this@QmmeApp)}",
            )
            if (reason !in forcedLogoutReasons) return
            RuntimeCoordinator.markLogout(
                runtime = RuntimeCoordinator.currentRuntime(),
                reason = "official:${reason ?: "unknown"}",
                source = "QmmeApp.logoutCallback",
            )
            clearExpiredLoginState()
            _logoutReason.value = reason
            Log.w("QMME", "account: official logout reason=$reason")
        }
    }

    companion object {
        @Volatile
        var sAppRuntime: AppRuntime? = null
        private val _logoutReason = MutableStateFlow<Constants.LogoutReason?>(null)
        val logoutReason = _logoutReason.asStateFlow()
        private val forcedLogoutReasons = setOf(
            Constants.LogoutReason.expired,
            Constants.LogoutReason.forceLogout,
            Constants.LogoutReason.kicked,
            Constants.LogoutReason.secKicked,
            Constants.LogoutReason.suspend
        )
        private val loginRestartScheduled = AtomicBoolean(false)

        fun markLoginEstablished() {
            _logoutReason.value = null
        }

        /**
         * Native screens consume the one-shot forced-offline signal before an
         * Activity recreation. The durable account record was already cleared
         * by [IAccountCallback.onLogout].
         */
        fun acknowledgeOfficialLogout(reason: Constants.LogoutReason) {
            if (_logoutReason.value == reason) {
                _logoutReason.value = null
            }
        }

        /**
         * Bind the account returned by WtLogin to the embedded MobileQQ runtime.
         * LoginPrefs is written by the activity before the optional process restart;
         * this method updates the in-memory/runtime side immediately as well.
         */
        fun bindLoggedInAccount(account: SimpleAccount): String {
            val uin = runCatching { account.uin }.getOrNull().orEmpty()
            if (uin.isBlank()) return "invalid account"
            val mobile = sMobileQQ ?: return "MobileQQ null"
            return runCatching {
                mobile.setLastLoginUin(uin)
                mobile.setSortAccountList(arrayListOf(account))
                val initialRuntime = ensureRuntime(mobile) ?: return "runtime null"
                RuntimeCoordinator.markAccountBinding(
                    runtime = initialRuntime,
                    uin = uin,
                    source = "QmmeApp.bindLoggedInAccount",
                )
                val initialUin = runCatching { initialRuntime.currentUin }.getOrNull().orEmpty()
                val alreadyBound = initialRuntime.isLogin() && initialUin == uin
                val runtime = if (alreadyBound) {
                    initialRuntime
                } else {
                    // AppRuntime.login() schedules MobileQQ.createNewRuntime().
                    // Do not set the old runtime's login bit or publish it as the
                    // new account; wait for MobileQQ to publish the replacement.
                    initialRuntime.login(account)
                    awaitRuntimeForAccount(mobile, initialRuntime, uin)
                } ?: return "runtime switch pending"

                val loginOk = runCatching { runtime.isLogin() }.getOrDefault(false)
                RuntimeCoordinator.markAccountBound(
                    runtime = runtime,
                    uin = uin,
                    source = "QmmeApp.bindLoggedInAccount",
                )
                sAppRuntime = runtime
                RuntimeCoordinator.observeLegacyMirror(
                    sAppRuntime,
                    source = "QmmeApp.bindLoggedInAccount",
                )
                Log.d(
                    "QMME",
                    "account: bound uin=${RuntimeCoordinator.redactUin(uin)} " +
                            "runtimeIdentity=${System.identityHashCode(runtime)} isLogin=$loginOk " +
                            "reused=$alreadyBound",
                )
                if (loginOk) "ok" else "runtime not logged in"
            }.getOrElse { error ->
                Log.e(
                    "QMME",
                    "account: bind failed uin=${RuntimeCoordinator.redactUin(uin)}",
                    error,
                )
                "failed: ${error.javaClass.simpleName}: ${error.message}"
            }
        }

        private fun awaitRuntimeForAccount(
            app: MobileQQ,
            previous: AppRuntime?,
            uin: String,
            timeoutMillis: Long = 15_000L,
        ): AppRuntime? {
            val deadline = System.currentTimeMillis() + timeoutMillis
            while (System.currentTimeMillis() < deadline) {
                val candidate = runCatching { app.peekAppRuntime() }.getOrNull()
                if (candidate != null && candidate !== previous) {
                    sAppRuntime = candidate
                    RuntimeCoordinator.observeRuntime(
                        candidate,
                        source = "QmmeApp.awaitRuntimeForAccount",
                    )
                    val candidateUin = runCatching { candidate.currentUin }.getOrNull().orEmpty()
                    val candidateLoggedIn = runCatching { candidate.isLogin() }.getOrDefault(false)
                    if (candidateUin == uin && candidateLoggedIn) return candidate
                }
                Thread.sleep(100L)
            }
            return null
        }

        /**
         * Restore the app-owned persisted account into MobileQQ before creating the
         * business runtime.  This makes the SharedPreferences record effective after
         * the post-login process restart instead of merely displaying it in the UI.
         */
        private fun restoreStoredAccount(): SimpleAccount? {
            val app = sMobileQQ ?: return null
            val account = runCatching { LoginPrefs.loadAccount(app) }.getOrNull() ?: return null
            val uin = runCatching { account.uin }.getOrNull().orEmpty()
            if (uin.isBlank()) return null
            runCatching { app.setLastLoginUin(uin) }
            runCatching { app.setSortAccountList(arrayListOf(account)) }
            Log.i(
                "QMME",
                "account: restored persisted uin=${RuntimeCoordinator.redactUin(uin)}",
            )
            return account
        }

        /**
         * 登录完成后重启主进程，让 MobileQQ、KernelService 和 MsgService 从全新生命周期初始化。
         * 账号必须在调用前落盘；旧进程只负责安排启动并退出，不再尝试复用半初始化的 NT 对象。
         */
        fun restartAfterLogin(context: Context): Boolean {
            if (!loginRestartScheduled.compareAndSet(false, true)) return false
            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP,
                    )
                }
                ?: run {
                    loginRestartScheduled.set(false)
                    return false
                }
            val pendingOptions = if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions.makeBasic().apply {
                    setPendingIntentCreatorBackgroundActivityStartMode(
                        if (Build.VERSION.SDK_INT >= 35) {
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                        } else {
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        },
                    )
                }.toBundle()
            } else {
                null
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0x514D,
                launchIntent,
                PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                pendingOptions,
            )
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                ?: run {
                    loginRestartScheduled.set(false)
                    return false
                }
            val triggerAt = SystemClock.elapsedRealtime() + 1_500L
            val scheduled = runCatching {
                if (Build.VERSION.SDK_INT >= 31 && !alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent,
                    )
                } else {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerAt,
                        pendingIntent,
                    )
                }
            }.isSuccess
            if (!scheduled) {
                loginRestartScheduled.set(false)
                Log.e("QMME", "login restart: failed to schedule alarm")
                return false
            }
            Log.i(
                "QMME",
                "login restart: scheduled ${triggerAt - SystemClock.elapsedRealtime()}ms " +
                        "component=${launchIntent.component}",
            )
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { (context as? Activity)?.finishAndRemoveTask() }
                Process.killProcess(Process.myPid())
            }, 700L)
            return true
        }

        fun forceExit(context: Context) {
            Handler(Looper.getMainLooper()).post {
                runCatching { (context as? Activity)?.finishAndRemoveTask() }
                Process.killProcess(Process.myPid())
                exitProcess(0)
            }
        }

        fun resetRuntimeAfterLogout(app: MobileQQ? = sMobileQQ) {
            val runtime = RuntimeCoordinator.currentRuntime()
                ?: sAppRuntime
                ?: runCatching { app?.peekAppRuntime() }.getOrNull()
            RuntimeCoordinator.markLogout(
                runtime = runtime,
                reason = "resetRuntimeAfterLogout",
                source = "QmmeApp.resetRuntimeAfterLogout",
            )
            KernelBridge.clearServiceCache("QmmeApp.resetRuntimeAfterLogout")
            sAppRuntime = null
            if (app == null) {
                RuntimeCoordinator.clearRuntime(
                    runtime = runtime,
                    source = "QmmeApp.resetRuntimeAfterLogout.noMobileQQ",
                )
                return
            }
            runCatching { app.setSortAccountList(emptyList()) }
            runCatching { app.lastLoginUin = "" }
            runCatching {
                val runtimeField = MobileQQ::class.java.getDeclaredField("mAppRuntime")
                runtimeField.isAccessible = true
                runtimeField.set(app, null)
            }
            runCatching {
                val stateField = MobileQQ::class.java.getDeclaredField("mRuntimeState")
                stateField.isAccessible = true
                (stateField.get(app) as? AtomicInteger)?.set(STATE_EMPTY)
            }
            runCatching {
                val ntInitUinField = MobileQQ::class.java.getDeclaredField("ntInitUin")
                ntInitUinField.isAccessible = true
                ntInitUinField.set(app, null)
            }
            RuntimeCoordinator.clearRuntime(
                runtime = runtime,
                source = "QmmeApp.resetRuntimeAfterLogout",
            )
        }

        fun ensureRuntime(app: MobileQQ? = sMobileQQ): AppRuntime? {
            val coordinatedRuntime = RuntimeCoordinator.currentRuntime()
            if (coordinatedRuntime != null && coordinatedRuntime !== sAppRuntime) {
                Log.w(
                    "QMME",
                    "runtime: legacy mirror mismatch; adopting coordinator runtime " +
                            "generation=${RuntimeCoordinator.currentSession()?.generation} " +
                            "runtimeIdentity=${System.identityHashCode(coordinatedRuntime)}",
                )
                sAppRuntime = coordinatedRuntime
            }
            sAppRuntime?.let {
                RuntimeCoordinator.observeRuntime(it, source = "QmmeApp.ensureRuntime.cached")
                RuntimeCoordinator.observeLegacyMirror(it, source = "QmmeApp.ensureRuntime.cached")
                return it
            }

            val mobile = app ?: return null
            val processName = runCatching { mobile.qqProcessName }.getOrNull()
            if (BuildConfig.APPLICATION_ID != processName) {
                Log.d(
                    "QMME",
                    "ensureRuntime: not the main process processName=$processName",
                )
                return null
            }

            fun adopt(runtime: AppRuntime?, source: String): AppRuntime? {
                if (runtime == null) return null
                sAppRuntime = runtime
                RuntimeCoordinator.observeRuntime(
                    runtime,
                    processName = processName,
                    source = source,
                )
                RuntimeCoordinator.observeLegacyMirror(runtime, source = source)
                Log.d(
                    "QMME",
                    "ensureRuntime: source=$source identity=${System.identityHashCode(runtime)}, " +
                            "isRunning=${runCatching { runtime.isRunning }.getOrDefault(false)}, " +
                            "isLogin=${runCatching { runtime.isLogin() }.getOrDefault(false)}",
                )
                return runtime
            }

            // Keep MobileQQ's official ownership boundary. waitAppRuntime() either
            // returns the runtime created by MobileQQ.doInit/createNewRuntime or
            // waits for that initialization to publish mAppRuntime.
            adopt(
                runCatching { mobile.waitAppRuntime() }
                    .onFailure { Log.w("QMME", "ensureRuntime: waitAppRuntime failed", it) }
                    .getOrNull(),
                "QmmeApp.ensureRuntime.waitAppRuntime",
            )?.let { return it }

            // A ready runtime can be published between the wait and this read.
            adopt(
                runCatching { mobile.peekAppRuntime() }
                    .onFailure { Log.w("QMME", "ensureRuntime: peekAppRuntime failed", it) }
                    .getOrNull(),
                "QmmeApp.ensureRuntime.peekAppRuntime",
            )?.let { return it }

            // Do not construct an AppRuntime, set mRuntimeState, or call onCreate
            // ourselves. If MobileQQ is still empty, let its own initializer load
            // the persisted account and execute the normal createRuntime path.
            runCatching { mobile.doInit(true) }
                .onFailure { Log.e("QMME", "ensureRuntime: official doInit failed", it) }

            adopt(
                runCatching { mobile.waitAppRuntime() }
                    .onFailure { Log.w("QMME", "ensureRuntime: wait after doInit failed", it) }
                    .getOrNull(),
                "QmmeApp.ensureRuntime.waitAfterDoInit",
            )?.let { return it }
            return adopt(
                runCatching { mobile.peekAppRuntime() }.getOrNull(),
                "QmmeApp.ensureRuntime.peekAfterDoInit",
            )
        }
    }

    override fun attachBaseContext(base: Context) {
        RuntimeCoordinator.onApplicationAttach(
            context = base,
            processName = runCatching { currentProcessNameByActivityThread }.getOrNull(),
            source = "QmmeApp.attachBaseContext.start",
        )
        Log.d("QMME", "attachBaseContext start")
        LegacyKiller.installForCurrentPackage(base)   // PM proxy for package name mapping (always needed)
        PackageSignatureProvider.install()                 // new CREATOR hook for IPC signature
        if (isMainProcess()) {
            setMainProcessName(BuildConfig.APPLICATION_ID)
            // getQQProcessName() reads processName field, not PACKAGE_NAME
            runCatching {
                val f = MobileQQ::class.java.getDeclaredField("processName")
                f.isAccessible = true
                f.set(null, BuildConfig.APPLICATION_ID)
            }
        }
        runCatching { EmotionAssetBridge.ensure(base) }
            .onFailure { Log.e("QMME", "emotion asset bridge failed", it) }
        super.attachBaseContext(base)
        MultiDex.install(this)
        RuntimeCoordinator.onApplicationAttach(
            context = this,
            processName = runCatching { currentProcessNameByActivityThread }.getOrNull(),
            source = "QmmeApp.attachBaseContext.done",
        )
        Log.d("QMME", "attachBaseContext done")
    }

    override fun onCreate() {
        Log.d("QMME", "onCreate start")
        // The official ApplicationCreate stage dispatches CrashInitTask and
        // other worker tasks from MobileQQ.super.onCreate().  Initialize the
        // project QMMKV backend first so those tasks never observe the
        // transient "mmkvCreateInstance without init" state.
        initializeQmmkv()
        super.onCreate()
        RuntimeCoordinator.onApplicationCreate(
            context = this,
            processName = runCatching { currentProcessNameByActivityThread }.getOrNull(),
            source = "QmmeApp.onCreate.afterSuper",
        )
        Log.d("QMME", "onCreate super done")
        CrashCatcher.install(this)
        Log.d("QMME", "crashcatcher init done")
        SignatureProbe.dump(this)
        // Kept idempotent for warm-starts and vendor process recreation.
        initializeQmmkv()
        if (isMainProcess()) {
            // Keep MobileQQ's own cold-start lifecycle intact.  In particular, do not
            // replay LoginPrefs here: setSortAccountList()/login() during Application
            // startup can make MobileQQ switch runtimes while MainService is still being
            // attached.  That leaves the UI holding a stale WatchAppInterface and can
            // terminate the process from MobileQQ with System.exit(-1).  LoginPrefs is
            // consumed by MainActivity, while the official runtime/account binding is
            // performed by KernelBridge after the UI is ready.
            ensureRuntime(this)
            initializeOfficialImageRuntime()
            registerLogoutCallback()
            OfficialReportBridge.initialize(this)
        }
    }

    private fun initializeQmmkv() {
        synchronized(QMMKV::class.java) {
            if (QMMKV.d) return
            QMMKV.e = MMKVHandlerImpl()
            runCatching {
                MMKV.t(this)
                MMKV.z(QMMKV.e)
                MMKV.y(QMMKV.e)
                QMMKV.d = true
                Log.d("QMME", "MMKV init OK")
            }.onFailure { Log.e("QMME", "MMKV init failed", it) }
        }
    }

    private fun initializeOfficialImageRuntime() {
        runCatching { System.loadLibrary("apng") }
            .onSuccess { Log.d("QMME", "libapng.so loaded") }
            .onFailure { Log.w("QMME", "libapng.so unavailable", it) }
        runCatching { System.loadLibrary("jlottie") }
            .onSuccess { Log.d("QMME", "libjlottie.so loaded") }
            .onFailure { Log.w("QMME", "libjlottie.so unavailable", it) }
        runCatching {
            val taskClass = Class.forName("com.tencent.qqnt.watch.startup.task.UrlDrawableInitTask")
            val task = taskClass.getDeclaredConstructor().newInstance()
            taskClass.getMethod("a", Context::class.java).invoke(task, this)
            Log.d("QMME", "URLDrawable runtime initialized")
        }.onFailure {
            Log.w("QMME", "URLDrawable runtime unavailable; emotion fallback remains enabled", it)
        }
    }

    private fun isMainProcess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            processName == BuildConfig.APPLICATION_ID
        } else (currentProcessNameByActivityThread
            ?: currentProcessNameByActivityManager
                ) == BuildConfig.APPLICATION_ID
    }

    /**
     * Get current process name.
     * Quicker than ActivityManager.
     */
    val currentProcessNameByActivityThread: String?
        @SuppressLint("DiscouragedPrivateApi", "PrivateApi")
        get() = runCatching {
            val declaredMethod: Method = Class.forName(
                "android.app.ActivityThread",
                false,
                Application::class.java.classLoader
            ).getDeclaredMethod("currentProcessName")
            declaredMethod.isAccessible = true
            declaredMethod.invoke(null) as String
        }.getOrNull()

    /**
     * Get current process name.
     * Slowest.
     */
    val currentProcessNameByActivityManager: String
        get() {
            val pid: Int = Process.myPid()
            val am = this.getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val runningAppList = am.runningAppProcesses
            for (processInfo in runningAppList) {
                if (processInfo.pid == pid) {
                    return processInfo.processName
                }
            }
            throw IllegalStateException("it is impossible")
        }

    fun clearLocalLoginState() {
        val persistedAccountCleared = LoginPrefs.clear(this)
        val runtime =
            RuntimeCoordinator.currentRuntime()
                ?: sAppRuntime
                ?: runCatching { sMobileQQ?.peekAppRuntime() }.getOrNull()
        RuntimeCoordinator.observeLegacyMirror(runtime, source = "QmmeApp.clearLocalLoginState")
        runCatching { runtime?.userLogoutReleaseData() }
            .onFailure { error -> Log.w("QMME", "account: release runtime failed", error) }
        resetRuntimeAfterLogout()
        Log.d(
            "QMME",
            "account: cleared runtime and persisted account, committed=$persistedAccountCleared",
        )
    }

    private fun clearExpiredLoginState() {
        clearLocalLoginState()
    }

    private fun registerLogoutCallback() {
        sMobileQQ?.registerAccountCallback(logoutCallback)
        Log.d("QMME", "account: logout callback registered")
    }


    override fun getPackageName(): String {
        // Only spoof for QQ signature/apk-id related code. Global spoofing breaks AndroidX
        // provider discovery and MSF service binding because framework APIs then look for
        // components under com.tencent.qqlite instead of the installed package.
        return if (isOriginalPackageNameCaller()) "com.tencent.qqlite" else BuildConfig.APPLICATION_ID
    }

    private fun isOriginalPackageNameCaller(): Boolean {
        return Thread.currentThread().stackTrace.any { frame ->
            val c = frame.className
            c.startsWith("oicq.wlogin_sdk.") ||
                    c.startsWith("com.tencent.mobileqq.msf.core.auth.") ||
                    c.startsWith("com.tencent.mobileqq.msf.core.net.") ||
                    c.contains("WtLogin") ||
                    c.contains("wlogin") ||
                    c == "rj.qmme.fix.SignatureProbe"
        }
    }

    override fun createRuntime(processName: String?, readyNew: Boolean): AppRuntime? {
        // Keep the apktool WatchApplicationDelegate contract: only the package
        // process owns a WatchAppInterface. MobileQQ is responsible for init(),
        // account restoration, setLogined(), onCreate(), and publishing
        // mAppRuntime; this factory must remain side-effect free beyond creating
        // the object and assigning its process suffix.
        if (processName != BuildConfig.APPLICATION_ID) return null
        val runtime = WatchAppInterface(this, processName)
        val suffix = MobileQQ.getProcessSuffix(processName, BuildConfig.APPLICATION_ID)
        runtime.setProcessName(suffix)
        sAppRuntime = runtime
        RuntimeCoordinator.registerRuntime(
            runtime = runtime,
            processName = processName,
            source = "QmmeApp.createRuntime",
        )
        Log.d(
            "QMME",
            "createRuntime: identity=${System.identityHashCode(runtime)} " +
                    "process=$processName suffix=$suffix readyNew=$readyNew",
        )
        return runtime
    }

    // QQ手表版 9.0.7 APK: com.tencent.common.config.AppSetting.a = 0x200646b9.
    override fun getAppId(processName: String?): Int = 537282233
    override fun getAppId(): Int = 537282233

    override fun getCustomGuid(): ByteArray? = runCatching {
        val guid = com.tencent.mobileqq.utils.KidInfoUtil.getGuid(this)
        com.tencent.mobileqq.utils.HexUtil.c(guid)
    }.onFailure { error ->
        Log.w("QMME", "getCustomGuid failed", error)
    }.getOrNull()

    // QQ 代码构造的 intent ComponentName 用 com.tencent.qqlite，但实际装的是 rj.qmme，
    // Android 找不到组件抛 SecurityException。拦截并修正包名。
    private fun fixIntent(intent: Intent?): Intent? {
        val cn = intent?.component ?: return intent
        if (cn.packageName == "com.tencent.qqlite") {
            intent.component =
                android.content.ComponentName(BuildConfig.APPLICATION_ID, cn.className)
        }
        return intent
    }

    override fun startService(service: Intent): android.content.ComponentName? {
        return super.startService(fixIntent(service)!!)
    }

    override fun startForegroundService(service: Intent): android.content.ComponentName? {
        return super.startForegroundService(fixIntent(service)!!)
    }

    override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
        return super.bindService(fixIntent(service)!!, conn, flags)
    }

    override fun registerReceiver(
        receiver: BroadcastReceiver?,
        filter: IntentFilter
    ): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            super.registerReceiver(receiver, filter, receiverExportFlag(filter))
        } else {
            super.registerReceiver(receiver, filter)
        }
    }

    override fun registerReceiver(
        receiver: BroadcastReceiver?,
        filter: IntentFilter,
        broadcastPermission: String?,
        scheduler: Handler?
    ): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            super.registerReceiver(
                receiver,
                filter,
                broadcastPermission,
                scheduler,
                receiverExportFlag(filter)
            )
        } else {
            super.registerReceiver(receiver, filter, broadcastPermission, scheduler)
        }
    }

    private fun receiverExportFlag(filter: IntentFilter): Int {
        val hasPlatformAction = (0 until filter.countActions()).any { index ->
            filter.getAction(index)?.startsWith("android.") == true
        }
        return if (hasPlatformAction) Context.RECEIVER_EXPORTED else Context.RECEIVER_NOT_EXPORTED
    }

    override fun isUserAllow(): Boolean = true
}
