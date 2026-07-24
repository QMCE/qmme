package rj.qmme.kernel

import android.util.Log
import com.tencent.mobileqq.app.guard.GuardManager
import com.tencent.mobileqq.qroute.QRoute
import com.tencent.qphone.base.remote.SimpleAccount
import com.tencent.qqnt.kernel.api.IKernelCreateListener
import com.tencent.qqnt.kernel.api.IGroupService
import com.tencent.qqnt.kernel.api.IKernelService
import com.tencent.qqnt.kernel.api.IServletAPI
import com.tencent.qqnt.kernel.nativeinterface.IKernelMsgService
import com.tencent.qqnt.kernel.nativeinterface.IKernelRecentContactService
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession
import com.tencent.qqnt.msg.api.IMsgPushForegroundApi
import com.tencent.qqnt.watch.mainframe.api.IMsfConnHelper
import com.tencent.qqnt.watch.mainframe.servlet.MsfConnPushServlet
import com.tencent.qqnt.watch.selftab.api.ISelfProfileRuntimeService
import mqq.app.AppRuntime
import mqq.app.Foreground
import mqq.app.MobileQQ
import mqq.app.NewIntent
import mqq.manager.TicketManager
import rj.qmme.QmmeApp
import rj.qmme.runtime.RuntimeCoordinator
import rj.qmme.diagnostics.OfflineDiagnostics
import rj.qmme.runtime.RuntimeSession
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "QMME"

object KernelBridge {
    @Volatile
    private var foregroundCallbackRegistered = false

    /** Keep the init-complete callback idempotent across official and late callbacks. */
    private val kernelInitCompleteNotified = AtomicBoolean(false)

    // 全局服务缓存
    @Volatile
    private var cachedKs: IKernelService? = null
    @Volatile
    private var cachedMsgService: com.tencent.qqnt.kernel.api.IMsgService? = null
    @Volatile
    private var cachedRecentService: com.tencent.qqnt.kernel.api.IRecentContactService? = null
    @Volatile
    private var cachedBuddyService: com.tencent.qqnt.kernel.api.IBuddyService? = null
    @Volatile
    private var cachedGroupService: com.tencent.qqnt.kernel.api.IGroupService? = null
    /** Runtime ownership of the service cache; null means the cache is cold. */
    @Volatile
    private var cachedRuntimeSession: RuntimeSession? = null
    @Volatile
    private var directMsgWrapper: com.tencent.qqnt.kernel.api.IMsgService? = null
    @Volatile
    private var directRecentWrapper: com.tencent.qqnt.kernel.api.IRecentContactService? = null
    @Volatile
    private var officialMessageKernel: com.tencent.qqnt.kernel.api.IMsgService? = null
    @Volatile
    private var officialMessageService: com.tencent.qqnt.msg.api.IMsgService? = null
    private val officialMessageLock = Any()
    private val serviceCacheLock = Any()

    private fun warnIfStaleCache(source: String) {
        val cached = cachedRuntimeSession
        val current = RuntimeCoordinator.currentSession()
        if (cached == null || current == null) return
        if (cached.generation != current.generation || cached.runtime !== current.runtime) {
            Log.w(
                TAG,
                "KernelBridge: stale service cache source=$source " +
                        "cachedGeneration=${cached.generation} cachedRuntimeIdentity=${cached.runtimeIdentity} " +
                        "currentGeneration=${current.generation} " +
                        "currentRuntimeIdentity=${current.runtimeIdentity}",
            )
        }
    }

    /**
     * A service is visible only while both the coordinator and the cache point at
     * the same concrete AppRuntime object and generation.  A warning alone is
     * not enough here: a late callback from a replaced runtime must not hand an
     * old native proxy back to a screen.
     */
    private fun ownedCacheSession(
        runtimeOverride: AppRuntime?,
        source: String,
    ): RuntimeSession? {
        val current = RuntimeCoordinator.currentSession()
        val requested = runtimeOverride?.let { RuntimeCoordinator.sessionFor(it) } ?: current
        val cached = cachedRuntimeSession
        if (current == null || requested == null || cached == null ||
            current.generation != requested.generation || current.runtime !== requested.runtime ||
            cached.generation != current.generation || cached.runtime !== current.runtime
        ) {
            warnIfStaleCache(source)
            Log.w(
                TAG,
                "KernelBridge: rejecting unowned service cache source=$source " +
                        "requestedGeneration=${requested?.generation ?: "none"} " +
                        "cachedGeneration=${cached?.generation ?: "none"} " +
                        "currentGeneration=${current?.generation ?: "none"}",
            )
            return null
        }
        return cached
    }

    private fun cacheSessionFor(
        runtimeOverride: AppRuntime?,
        source: String,
    ): RuntimeSession? {
        val runtime = runtimeOverride ?: RuntimeCoordinator.currentRuntime()
        val session = if (runtime == null) {
            RuntimeCoordinator.currentSession()
        } else {
            RuntimeCoordinator.sessionFor(runtime)
        }
        if (session == null) {
            Log.w(
                TAG,
                "KernelBridge: runtime has no coordinator session source=$source " +
                        "runtimeIdentity=${runtime?.let(System::identityHashCode) ?: "none"}",
            )
        }
        return session
    }

    private fun recordCacheOwnership(
        runtimeOverride: AppRuntime?,
        source: String,
    ): RuntimeSession? {
        val session = cacheSessionFor(runtimeOverride, source)
        val current = RuntimeCoordinator.currentSession()
        if (session == null || current == null ||
            session.generation != current.generation || session.runtime !== current.runtime
        ) {
            Log.w(
                TAG,
                "KernelBridge: refusing service cache ownership source=$source " +
                        "cacheGeneration=${session?.generation ?: "none"} " +
                        "currentGeneration=${current?.generation ?: "none"}",
            )
            return null
        }
        cachedRuntimeSession = session
        Log.i(
            TAG,
            "KernelBridge: service cache ownership source=$source " +
                    "generation=${session.generation} runtimeIdentity=${session.runtimeIdentity}",
        )
        return session
    }

    /** Clear every project-side proxy when a runtime is replaced or logged out. */
    fun clearServiceCache(source: String = "clearServiceCache") {
        synchronized(serviceCacheLock) {
            cachedKs = null
            cachedMsgService = null
            cachedRecentService = null
            cachedBuddyService = null
            cachedGroupService = null
            cachedRuntimeSession = null
            directMsgWrapper = null
            directRecentWrapper = null
            synchronized(officialMessageLock) {
                officialMessageKernel = null
                officialMessageService = null
            }
            kernelInitCompleteNotified.set(false)
            foregroundCallbackRegistered = false
            msfConnectionBridgeRegistered = false
            msfConnectionListener = null
            foregroundReplayedSession = null
            Log.d(TAG, "KernelBridge: service cache cleared source=$source")
        }
    }

    private fun cachedKernelServiceFor(
        runtime: AppRuntime?,
        source: String,
    ): IKernelService? {
        if (cachedKs == null) return null
        return ownedCacheSession(runtime, source)?.let { cachedKs }
    }

    private fun <T> cachedServiceFor(
        service: T?,
        runtime: AppRuntime?,
        source: String,
    ): T? {
        if (service == null) return null
        return ownedCacheSession(runtime, source)?.let { service }
    }

    fun getKernelService(): IKernelService? = cachedKernelServiceFor(
        RuntimeCoordinator.currentRuntime(),
        "getKernelService",
    )

    fun getMsgService(): com.tencent.qqnt.kernel.api.IMsgService? = cachedServiceFor(
        cachedMsgService,
        RuntimeCoordinator.currentRuntime(),
        "getMsgService",
    )
    fun getKernelMsgService(): IKernelMsgService? = runCatching {
        warnIfStaleCache("getKernelMsgService")
        val kernelService = cachedKernelServiceFor(
            runtime = RuntimeCoordinator.currentRuntime(),
            source = "getKernelMsgService",
        ) ?: return@runCatching null
        val wrapperSession = kernelService.javaClass
            .getDeclaredField("wrapperSession")
            .apply { isAccessible = true }
            .get(kernelService) as? IQQNTWrapperSession
        wrapperSession?.msgService
    }.getOrNull()
    fun getRecentContactService(): com.tencent.qqnt.kernel.api.IRecentContactService? =
        cachedServiceFor(
            cachedRecentService,
            RuntimeCoordinator.currentRuntime(),
            "getRecentContactService",
        )

    /**
     * Re-read the Java service wrappers after NT startup.  KernelServiceImpl
     * eagerly touches its ClearableLazy values from initService(); if libkernel
     * is still bringing a module up at that exact instant, those values can be
     * constructed around a null native proxy.  We therefore use the direct
     * wrapper-session native delegates and construct a fresh public service
     * wrapper when necessary.
     */
    fun refreshCoreServices(runtimeOverride: AppRuntime? = null): Boolean {
        val runtime = runtimeOverride ?: QmmeApp.ensureRuntime()
        val kernelService = cachedKernelServiceFor(runtime, "refreshCoreServices") ?: runCatching {
            runtime?.getRuntimeService(IKernelService::class.java, "")
        }.getOrNull()
        if (kernelService == null) return false
        cacheServices(kernelService, runtime, "refreshCoreServices")
        return cachedServiceFor(cachedMsgService, runtime, "refreshCoreServices.msg") != null &&
                cachedServiceFor(cachedRecentService, runtime, "refreshCoreServices.recent") != null
    }

    /** True when a Java BaseService has a live native service behind it. */
    fun isNativeServiceReady(service: Any?): Boolean = hasNativeService(service)

    fun getBuddyService(): com.tencent.qqnt.kernel.api.IBuddyService? = cachedServiceFor(
        cachedBuddyService,
        RuntimeCoordinator.currentRuntime(),
        "getBuddyService",
    )

    fun getGroupService(): com.tencent.qqnt.kernel.api.IGroupService? = cachedServiceFor(
        cachedGroupService,
        RuntimeCoordinator.currentRuntime(),
        "getGroupService",
    )
    fun ensureOfficialMessageBridge(
        runtimeOverride: AppRuntime? = null,
    ): com.tencent.qqnt.msg.api.IMsgService? {
        val runtime = runtimeOverride ?: QmmeApp.ensureRuntime()
        val kernelService = cachedKernelServiceFor(runtime, "ensureOfficialMessageBridge") ?: runCatching {
            runtime?.getRuntimeService(IKernelService::class.java, "")
        }.getOrNull()
        val kernelMsgService = cachedServiceFor(
            cachedMsgService,
            runtime,
            "ensureOfficialMessageBridge.cached",
        ) ?: runCatching {
            kernelService?.getMsgService()
        }.getOrNull() ?: return null

        officialMessageService?.let { cached ->
            if (officialMessageKernel === kernelMsgService) return cached
        }

        return synchronized(officialMessageLock) {
            officialMessageService?.let { cached ->
                if (officialMessageKernel === kernelMsgService) return@synchronized cached
            }
            runCatching {
                val messageBridge = QRoute.api(com.tencent.qqnt.msg.api.IMsgService::class.java)
                messageBridge.init(kernelMsgService)
                officialMessageKernel = kernelMsgService
                officialMessageService = messageBridge
                Log.d(TAG, "KernelBridge: official message bridge initialized service=$messageBridge")
                messageBridge
            }.onFailure { error ->
                Log.w(TAG, "KernelBridge: official message bridge initialization failed", error)
            }.getOrNull()
        }
    }
    fun getSelfProfileService(): ISelfProfileRuntimeService? = runCatching {
        QmmeApp.ensureRuntime()
            ?.getRuntimeService(ISelfProfileRuntimeService::class.java, "")
    }.getOrNull()

    fun awaitCoreServices(
        timeoutMillis: Long = 15_000,
        runtimeOverride: AppRuntime? = null,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val runtime = runtimeOverride ?: QmmeApp.ensureRuntime()
            val kernelService = cachedKernelServiceFor(runtime, "awaitCoreServices") ?: runCatching {
                runtime?.getRuntimeService(IKernelService::class.java, "")
            }.getOrNull()
            if (kernelService != null) {
                cacheServices(kernelService, runtime, "awaitCoreServices")
                if (cachedServiceFor(cachedMsgService, runtime, "awaitCoreServices.msg") != null &&
                    cachedServiceFor(cachedRecentService, runtime, "awaitCoreServices.recent") != null
                ) {
                    Log.d(TAG, "KernelBridge: core services ready")
                    return true
                }
            }
            Thread.sleep(250)
        }
        Log.w(
            TAG,
            "KernelBridge: timed out waiting for core services; " +
                    "runtime=${runtimeOverride ?: QmmeApp.sAppRuntime}, " +
                    "ks=$cachedKs, msg=$cachedMsgService, recent=$cachedRecentService"
        )
        return false
    }

    fun awaitGroupService(
        timeoutMillis: Long = 15_000,
        runtimeOverride: AppRuntime? = null,
    ): IGroupService? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val runtime = runtimeOverride ?: QmmeApp.ensureRuntime()
            val kernelService = cachedKernelServiceFor(runtime, "awaitGroupService") ?: runCatching {
                runtime?.getRuntimeService(IKernelService::class.java, "")
            }.getOrNull()
            if (kernelService != null) {
                cacheServices(kernelService, runtime, "awaitGroupService")
                cachedServiceFor(cachedGroupService, runtime, "awaitGroupService")?.let { return it }
            }
            Thread.sleep(250)
        }
        Log.w(TAG, "KernelBridge: timed out waiting for group service")
        return cachedServiceFor(
            cachedGroupService,
            runtimeOverride ?: RuntimeCoordinator.currentRuntime(),
            "awaitGroupService.timeout",
        )
    }

    /** bind 完成后由 waitForSession 调用，缓存各子 service.
     *
     * Keep this path identical to QMCE-Lite-X: only ask KernelServiceImpl for
     * its public service wrappers.  Calling the direct CppProxy service
     * getters here is not harmless; they cross the qmce/watch JNI boundary a
     * second time while the NTSdk thread is still finishing startup and were
     * the last operation before the stack-corruption abort.
     */
    private fun cacheServices(
        ks: IKernelService,
        runtimeOverride: AppRuntime? = null,
        source: String = "cacheServices",
    ) = synchronized(serviceCacheLock) {
        if (recordCacheOwnership(runtimeOverride, source) == null) {
            clearServiceCache("reject:$source")
            return@synchronized
        }
        cachedKs = ks
        completeExistingKernelInit(ks)

        Log.i(TAG, "KernelBridge: before official getMsgService")
        val nextMsgService = runCatching { ks.getMsgService() }
            .onFailure { Log.w(TAG, "KernelBridge: official getMsgService failed", it) }
            .getOrNull()
        Log.i(TAG, "KernelBridge: after official getMsgService: $nextMsgService")
        if (cachedMsgService !== nextMsgService) {
            synchronized(officialMessageLock) {
                officialMessageKernel = null
                officialMessageService = null
            }
        }
        cachedMsgService = nextMsgService

        Log.i(TAG, "KernelBridge: before official getRecentContactService")
        val nextRecentService = runCatching { ks.getRecentContactService() }
            .onFailure {
                Log.w(TAG, "KernelBridge: official getRecentContactService failed", it)
            }
            .getOrNull()
        Log.i(TAG, "KernelBridge: after official getRecentContactService: $nextRecentService")
        cachedRecentService = nextRecentService
        cachedBuddyService = runCatching { ks.getBuddyService() }.getOrNull()
        cachedGroupService = runCatching { ks.getGroupService() }.getOrNull()
        Log.d(
            TAG,
            "KernelBridge: cached services — ks=$cachedKs, msg=$cachedMsgService, " +
                    "recent=$cachedRecentService, buddy=$cachedBuddyService, " +
                    "group=$cachedGroupService, state=${kernelState(ks)}, " +
                    "cacheGeneration=${cachedRuntimeSession?.generation ?: "none"}, " +
                    "cacheRuntimeIdentity=${cachedRuntimeSession?.runtimeIdentity ?: "none"}",
        )
    }

    private fun nativeDelegate(service: Any?): Any? {
        if (service == null) return null
        val getter = service.javaClass.methods.firstOrNull {
            it.name == "getService" && it.parameterTypes.isEmpty()
        } ?: return service
        return runCatching { getter.invoke(service) }.getOrNull()
    }

    private fun hasNativeService(service: Any?): Boolean {
        val delegate = nativeDelegate(service) ?: return false
        return hasNativeHandle(delegate)
    }

    private fun sameNativeDelegate(first: Any?, second: Any?): Boolean {
        if (first == null || second == null) return false
        val firstHandle = nativeHandle(first)
        val secondHandle = nativeHandle(second)
        return if (firstHandle != null && secondHandle != null) {
            firstHandle == secondHandle && firstHandle != 0L
        } else {
            first === second
        }
    }

    private fun nativeHandle(delegate: Any?): Long? {
        if (delegate == null) return null
        var type: Class<*>? = delegate.javaClass
        while (type != null) {
            val field = type.declaredFields.firstOrNull { it.name == "nativeRef" }
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.getLong(delegate)
                }.getOrNull()
            }
            type = type.superclass
        }
        return null
    }

    private fun hasNativeHandle(delegate: Any?): Boolean {
        if (delegate == null) return false
        val handle = nativeHandle(delegate)
        if (handle != null) return handle != 0L
        // Non-CppProxy implementations do not expose nativeRef; their
        // non-null object is the best liveness signal available.
        return true
    }

    /**
     * 某些冷启动路径会先创建 wrapperSession，再错过 KernelServiceImpl 的 session listener；
     * native 层已经能收消息，但 isNTStartFinish 仍是 false，官方 service getter 因此全部返回 null。
     * 官方的 onSessionInitComplete 最终只做两件事：调用私有 initService()，再继续网络状态初始化。
     * 这里仅在 session 已存在且 init 标志未完成时补做同一个幂等初始化，不伪造 service 对象。
     */
    private fun completeExistingKernelInit(ks: IKernelService) {
        runCatching {
            val impl = Class.forName("com.tencent.qqnt.kernel.api.impl.KernelServiceImpl")
            val wrapperField = impl.getDeclaredField("wrapperSession").apply { isAccessible = true }
            val wrapper = wrapperField.get(ks)
            if (wrapper == null) {
                Log.d(
                    TAG,
                    "KernelBridge: completeExistingKernelInit skipped; wrapperSession=null ks=$ks"
                )
                return@runCatching
            }
            val readyField = impl.getDeclaredField("isNTStartFinish").apply { isAccessible = true }
            val ready =
                (readyField.get(ks) as? java.util.concurrent.atomic.AtomicBoolean)?.get() == true
            if (ready) {
                Log.d(TAG, "KernelBridge: existing kernel already ready ks=$ks wrapper=$wrapper")
                return@runCatching
            }
            Log.w(
                TAG,
                "KernelBridge: existing wrapper session found with isNTStartFinish=false; completing init"
            )
            impl.getDeclaredMethod("initService").apply { isAccessible = true }.invoke(ks)
            val after =
                (readyField.get(ks) as? java.util.concurrent.atomic.AtomicBoolean)?.get() == true
            Log.i(TAG, "KernelBridge: forced existing kernel init complete=$after wrapper=$wrapper")
        }.onFailure {
            Log.e(TAG, "KernelBridge: forced existing kernel init failed", it)
        }
    }

    private fun kernelState(ks: IKernelService): String {
        return runCatching {
            val impl = Class.forName("com.tencent.qqnt.kernel.api.impl.KernelServiceImpl")
            val wrapper =
                impl.getDeclaredField("wrapperSession").apply { isAccessible = true }.get(ks)
            val ready =
                (impl.getDeclaredField("isNTStartFinish").apply { isAccessible = true }.get(ks)
                        as? java.util.concurrent.atomic.AtomicBoolean)?.get()
            "wrapper=${wrapper != null}, isNTStartFinish=$ready"
        }.getOrElse { "stateError=${it.javaClass.simpleName}" }
    }

    @Volatile
    private var msfConnectionBridgeRegistered = false

    private var msfConnectionListener: MsfConnectionListener? = null

    @Volatile
    private var foregroundReplayedSession: IQQNTWrapperSession? = null

    fun bindLoggedInAccount(uin: String, account: SimpleAccount): String {
        return runCatching {
            val app = MobileQQ.sMobileQQ ?: return "MobileQQ null"
            runCatching { app.setLastLoginUin(uin) }
            runCatching { app.setSortAccountList(arrayListOf(account)) }

            val initialRuntime = QmmeApp.ensureRuntime(app)
            RuntimeCoordinator.markAccountBinding(
                runtime = initialRuntime,
                uin = uin,
                source = "KernelBridge.bindLoggedInAccount",
            )
            val initialUin = runCatching { initialRuntime?.currentUin }.getOrNull().orEmpty()
            val alreadyBound = initialRuntime?.isLogin() == true && initialUin == uin
            val runtime = if (alreadyBound) {
                initialRuntime
            } else {
                // AppRuntime.login() posts MobileQQ.createNewRuntime() to the
                // runtime handler. It is not a local field setter; never mark
                // the old object logged in or start KernelService on it.
                initialRuntime?.login(account)
                awaitRuntimeForAccount(app, initialRuntime, uin)
            }

            if (runtime == null) {
                RuntimeCoordinator.markFailed(
                    runtime = RuntimeCoordinator.currentRuntime(),
                    source = "KernelBridge.bindLoggedInAccount",
                    reason = "official runtime switch did not publish in time",
                )
                return "runtime switch pending"
            }
            RuntimeCoordinator.markAccountBound(
                runtime = runtime,
                uin = uin,
                source = "KernelBridge.bindLoggedInAccount",
            )
            Log.d(
                TAG,
                "bind: active runtimeIdentity=${System.identityHashCode(runtime)} " +
                        "isLogin=${runCatching { runtime.isLogin() }.getOrDefault(false)} " +
                        "uin=${RuntimeCoordinator.redactUin(runtime.currentUin)} " +
                        "reused=$alreadyBound",
            )

            checkTicketStatus(runtime, uin)
            if (!awaitLoginTicketReady(runtime, uin)) {
                RuntimeCoordinator.markFailed(
                    runtime = runtime,
                    source = "KernelBridge.bindLoggedInAccount",
                    reason = "TicketManager login ticket not ready",
                )
                return "login ticket not ready"
            }
            val ks = runCatching {
                runtime.getRuntimeService(IKernelService::class.java, "")
            }.getOrNull()
            Log.d(TAG, "bind: kernelService=$ks runtime=${System.identityHashCode(runtime)}")
            if (ks == null) return "kernel service unavailable"

            RuntimeCoordinator.markKernelStarting(
                runtime = runtime,
                source = "KernelBridge.bindLoggedInAccount",
            )
            val existingSession = runCatching { ks.getWrapperSession() }.getOrNull()
            Log.d(TAG, "bind: existingSession=$existingSession")
            if (existingSession == null) {
                if (!startKernelSession(ks, runtime)) return "kernel start failed"
            } else {
                Log.d(TAG, "bind: session already exists, reusing without runtime patch")
                initExistingKernel(runtime, ks)
            }

            waitForSession(ks, runtime)
            val ready = reinitializeAfterLogin(runtime)
            if (!ready) {
                Log.w(TAG, "bind: kernel session started but core services are not ready")
                "kernel services unavailable"
            } else {
                "ok"
            }
        }.getOrElse {
            RuntimeCoordinator.markFailed(
                runtime = RuntimeCoordinator.currentRuntime(),
                source = "KernelBridge.bindLoggedInAccount",
                reason = "${it.javaClass.simpleName}: ${it.message}",
            )
            "failed: ${it.javaClass.simpleName}: ${it.message}"
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
                QmmeApp.sAppRuntime = candidate
                RuntimeCoordinator.observeRuntime(
                    candidate,
                    source = "KernelBridge.awaitRuntimeForAccount",
                )
                val candidateUin = runCatching { candidate.currentUin }.getOrNull().orEmpty()
                val candidateLoggedIn = runCatching { candidate.isLogin() }.getOrDefault(false)
                if (candidateUin == uin && candidateLoggedIn) return candidate
            }
            Thread.sleep(100L)
        }
        return null
    }

    fun reinitializeAfterLogin(runtime: AppRuntime?): Boolean {
        clearServiceCache("reinitializeAfterLogin")

        val coreReady = awaitCoreServices(runtimeOverride = runtime)
        if (!coreReady) {
            Log.w(TAG, "login reinitialize: core services unavailable")
            RuntimeCoordinator.markFailed(
                runtime = runtime,
                source = "KernelBridge.reinitializeAfterLogin",
                reason = "core services unavailable",
            )
            return false
        }

        // Do not invoke the current watch getBuddyListV2/contact refresh or
        // direct message sync here: the qmce native ABI uses different model
        // descriptors and can terminate the process asynchronously.  Native NT
        // startup itself remains initialized; page-level reads stay cache-only.
        Log.d(TAG, "login reinitialize: skip incompatible active sync calls")

        runCatching {
            val context = com.tencent.qphone.base.util.BaseApplication.getContext()
            context.sendBroadcast(
                android.content.Intent("com.tencent.mobileqq.action.ON_KERNEL_INIT_COMPLETE")
                    .setPackage(context.packageName)
            )
            Log.d(TAG, "login reinitialize: ON_KERNEL_INIT_COMPLETE sent")
        }.onFailure { Log.w(TAG, "login reinitialize: init broadcast failed", it) }

        val ready = cachedServiceFor(cachedMsgService, runtime, "reinitializeAfterLogin.msg") != null &&
                cachedServiceFor(cachedRecentService, runtime, "reinitializeAfterLogin.recent") != null
        if (ready) {
            RuntimeCoordinator.markKernelReady(
                runtime = runtime,
                source = "KernelBridge.reinitializeAfterLogin",
            )
        }
        return ready
    }

    private data class LoginTicketReadiness(
        val managerPresent: Boolean,
        val a2Length: Int,
        val d2Length: Int,
        val d2KeyLength: Int,
    ) {
        val ready: Boolean
            get() = managerPresent && a2Length > 0 && d2Length > 0 && d2KeyLength > 0

        fun diagnostics(): String =
            "managerPresent=$managerPresent a2Len=$a2Length d2Len=$d2Length d2KeyLen=$d2KeyLength"
    }

    /**
     * MobileQQ publishes the replacement AppRuntime before every account
     * manager has necessarily finished restoring its cached tickets. Starting
     * NT against that half-ready runtime lets SenderModule hand native startup
     * an empty SessionTicket; the observed result is an immediate
     * onUserTokenExpired(expired) followed by the startup crash. Gate startup
     * on the same TicketManager source used by the official SenderModule.
     */
    private fun awaitLoginTicketReady(
        runtime: AppRuntime,
        uin: String,
        timeoutMillis: Long = 15_000L,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var last = LoginTicketReadiness(false, 0, 0, 0)
        while (System.currentTimeMillis() < deadline) {
            if (!RuntimeCoordinator.isCurrent(runtime)) break
            last = readLoginTicketReadiness(runtime, uin)
            if (last.ready) {
                val details = "runtimeIdentity=${System.identityHashCode(runtime)} ${last.diagnostics()}"
                Log.i(TAG, "bind: login ticket ready $details")
                OfflineDiagnostics.record(
                    runtime.applicationContext,
                    "login_ticket_ready",
                    details,
                )
                return true
            }
            Thread.sleep(100L)
        }
        val details = "runtimeIdentity=${System.identityHashCode(runtime)} ${last.diagnostics()}"
        Log.e(TAG, "bind: login ticket readiness timed out $details")
        OfflineDiagnostics.record(
            runtime.applicationContext,
            "login_ticket_timeout",
            details,
        )
        return false
    }

    private fun readLoginTicketReadiness(runtime: AppRuntime, uin: String): LoginTicketReadiness {
        val manager = runCatching {
            runtime.getManager(AppRuntime.TICKET_MANAGER) as? TicketManager
        }.onFailure { Log.d(TAG, "bind: TicketManager probe failed", it) }.getOrNull()
            ?: return LoginTicketReadiness(false, 0, 0, 0)
        val a2 = runCatching { manager.getA2(uin) }
            .onFailure { Log.d(TAG, "bind: TicketManager A2 probe failed", it) }
            .getOrNull()
        val d2 = runCatching { manager.getD2Ticket(uin) }
            .onFailure { Log.d(TAG, "bind: TicketManager D2 probe failed", it) }
            .getOrNull()
        return LoginTicketReadiness(
            managerPresent = true,
            a2Length = a2?.length ?: 0,
            d2Length = d2?._sig?.size?.times(2) ?: 0,
            d2KeyLength = d2?._sig_key?.size?.times(2) ?: 0,
        )
    }

    private fun checkTicketStatus(runtime: AppRuntime?, uin: String) {
        runCatching {
            val ticketClass =
                Class.forName("com.tencent.qqnt.account.login.api.ITicketRuntimeService")
            val ticketService = runCatching {
                runtime?.javaClass?.methods?.firstOrNull {
                    it.name == "getRuntimeService" && it.parameterTypes.size == 2
                }?.invoke(runtime, ticketClass, "")
            }.getOrNull()
            Log.d(TAG, "bind: ticket service present=${ticketService != null}")
            if (ticketService != null) {
                runCatching {
                    ticketService.javaClass.getMethod("getA2", String::class.java)
                        .invoke(ticketService, uin)
                }.onFailure { Log.d(TAG, "bind: A2 probe unavailable", it) }
                runCatching {
                    ticketService.javaClass.getMethod(
                        "getLocalTicket",
                        String::class.java,
                        Int::class.javaPrimitiveType,
                    ).invoke(ticketService, uin, 262144)
                }.onFailure { Log.d(TAG, "bind: local ticket probe unavailable", it) }
            }
        }.onFailure { Log.w(TAG, "bind: ticket check failed", it) }
    }

    private val nativeKernelLibraryLock = Any()
    @Volatile
    private var nativeKernelLibrariesLoaded = false

    /**
     * Load the Watch NT JNI dependency chain explicitly before KernelService.start.
     * The old path relied on KernelSetterImpl's static initializer and could call
     * startup JNI before the exported CppProxy symbol was visible.
     */
    private fun ensureNativeKernelLibraries(): Boolean {
        if (nativeKernelLibrariesLoaded) return true
        synchronized(nativeKernelLibraryLock) {
            if (nativeKernelLibrariesLoaded) return true
            val libraries = listOf(
                "basic_share",
                "djinni_support_lib",
                "module_service",
                "djinni_interface_core_public",
                // Official Watch KernelSetterImpl loads the logical "gpro"
                // module here; InitialModuleInjector maps it to gprowrapper.
                // A DT_NEEDED edge may map libgprowrapper.so as a dependency,
                // but it does not replace this Java loadLibrary call and its
                // JNI_OnLoad initialization. Starting libstartup against the
                // merely-mapped library leaves its timer/scheduler globals null
                // and crashes in TimerBase::PostNewScheduledTask.
                "gprowrapper",
                // libstartup.so has a DT_NEEDED reference to the exported
                // INTSessionShell factory in libwrapper.so.  The official
                // startup path loads wrapper before startup; omitting it makes
                // Android's linker reject libstartup with a missing C++ symbol.
                "wrapper",
                // The project previously bundled QQMax's old libkernel.so.
                // Official Watch 9.0.7 maps the kernel module to libwrapper.so;
                // loading both exports the same CppProxy JNI symbols and can
                // bind Java calls to the incompatible old implementation.
                "startup",
            )
            return try {
                libraries.forEach { library ->
                    System.loadLibrary(library)
                    Log.d(TAG, "KernelBridge: loaded lib$library.so")
                    OfflineDiagnostics.record(
                        RuntimeCoordinator.currentRuntime()?.applicationContext,
                        "native_library_loaded",
                        "library=$library",
                    )
                }
                val runtime = RuntimeCoordinator.currentRuntime()
                val context = runtime?.applicationContext
                if (runtime == null || context == null || !ProjectKernelBootstrap.initialize(context, runtime)) {
                    Log.e(TAG, "KernelBridge: project wrapper-engine bootstrap failed")
                    false
                } else {
                    nativeKernelLibrariesLoaded = true
                    true
                }
            } catch (error: Throwable) {
                // Do not mark the chain loaded after a partial failure. A later
                // retry can safely re-enter System.loadLibrary for already loaded
                // names and continue from the missing dependency.
                Log.e(TAG, "KernelBridge: native kernel library chain failed", error)
                false
            }
        }
    }

    /**
     * Start the already-created KernelService directly. This is the project
     * bridge: it does not construct KernelSetterImpl, call ensureInject(), patch
     * serviceContent, or install an official account callback.
     */
    private fun startKernelSession(ks: IKernelService, runtime: AppRuntime?): Boolean {
        val boundRuntime = runtime ?: run {
            Log.e(TAG, "bind: cannot start kernel session without runtime")
            return false
        }
        if (!RuntimeCoordinator.isCurrent(boundRuntime)) {
            Log.w(TAG, "bind: refusing to start kernel on stale runtime")
            return false
        }
        if (!ensureNativeKernelLibraries()) return false

        runCatching {
            val impl = ks as? com.tencent.qqnt.kernel.api.impl.KernelServiceImpl
            if (impl == null) {
                Log.e(TAG, "bind: KernelService is not the expected Watch implementation: $ks")
                return false
            }
            ProjectKernelDependencies.install(impl, boundRuntime)
        }.onFailure {
            Log.e(TAG, "bind: project kernel dependency installation failed", it)
            return false
        }

        kernelInitCompleteNotified.set(false)
        val listener = object : IKernelCreateListener {
            // Kotlin metadata in the Watch jar exposes descriptive names, while
            // the shipped JVM interface retains the obfuscated a/b symbols.
            // Implement both surfaces: native/JNI dispatches a/b directly.
            override fun onKernelSessionCreated(callbackRuntime: AppRuntime) {
                handleKernelSessionCreated(callbackRuntime)
            }

            override fun onKernelInitComplete(callbackRuntime: AppRuntime) {
                handleKernelInitComplete(callbackRuntime)
            }

            fun a(callbackRuntime: AppRuntime) {
                handleKernelSessionCreated(callbackRuntime)
            }

            fun b(callbackRuntime: AppRuntime) {
                handleKernelInitComplete(callbackRuntime)
            }

            private fun handleKernelSessionCreated(callbackRuntime: AppRuntime) {
                if (!RuntimeCoordinator.isCurrent(boundRuntime)) {
                    Log.w(TAG, "bind: kernel created callback belongs to stale runtime")
                    return
                }
                Log.d(
                    TAG,
                    "bind: project IKernelCreateListener.a runtime=" +
                            System.identityHashCode(callbackRuntime),
                )
            }

            private fun handleKernelInitComplete(callbackRuntime: AppRuntime) {
                if (!RuntimeCoordinator.isCurrent(boundRuntime)) {
                    Log.w(TAG, "bind: kernel complete callback belongs to stale runtime")
                    return
                }
                if (!kernelInitCompleteNotified.compareAndSet(false, true)) {
                    Log.d(TAG, "bind: project IKernelCreateListener.b ignored (duplicate)")
                    return
                }
                Log.d(
                    TAG,
                    "bind: project IKernelCreateListener.b runtime=" +
                            System.identityHashCode(callbackRuntime),
                )
                // Keep b lightweight. KernelServiceImpl invokes it from its
                // native completion callback; service getters are re-read from
                // the owning worker in waitForSession/reinitializeAfterLogin.
                registerDirectMsfConnectionBridge(boundRuntime)
                registerOfficialForegroundCallback(boundRuntime)
                runCatching {
                    val context = com.tencent.qphone.base.util.BaseApplication.getContext()
                    context.sendBroadcast(
                        android.content.Intent("com.tencent.mobileqq.action.ON_KERNEL_INIT_COMPLETE")
                            .setPackage(context.packageName),
                    )
                }.onFailure { Log.w(TAG, "bind: init-complete broadcast failed", it) }
            }
        }

        return runCatching {
            OfflineDiagnostics.record(
                boundRuntime.applicationContext,
                "kernel_start_enter",
                "runtimeIdentity=${System.identityHashCode(boundRuntime)}",
            )
            ks.start(listener)
            OfflineDiagnostics.record(
                boundRuntime.applicationContext,
                "kernel_start_returned",
                "runtimeIdentity=${System.identityHashCode(boundRuntime)}",
            )
            Log.i(TAG, "bind: project KernelService.start(listener) requested")
            true
        }.onFailure {
            Log.e(TAG, "bind: project KernelService.start failed", it)
        }.getOrDefault(false)
    }

    private fun registerOfficialForegroundCallback(runtime: AppRuntime?) {
        if (foregroundCallbackRegistered || runtime == null) return
        runCatching {
            runtime.getRuntimeService(IMsgPushForegroundApi::class.java, "")
        }.onSuccess { api ->
            if (api != null) {
                api.registerForegroundCallback()
                foregroundCallbackRegistered = true
                Log.d(TAG, "bind: official foreground callback registered api=$api")
            } else {
                Log.w(TAG, "bind: official foreground api unavailable")
            }
        }.onFailure { error ->
            Log.w(TAG, "bind: official foreground callback unavailable", error)
        }
    }

    private fun initializeOfficialMessageBridge(runtime: AppRuntime?) {
        ensureOfficialMessageBridge(runtime)
    }


    private fun registerDirectMsfConnectionBridge(runtime: AppRuntime?) {
        if (msfConnectionBridgeRegistered || runtime == null) return
        runCatching {
            val helper = QRoute.api(IMsfConnHelper::class.java)
            val listener = MsfConnectionListener(runtime)

            // Reproduce the safe part of MsfConnHelperImpl.initMsfConnPush().
            // The original method additionally installs Watch NetworkListener,
            // which reaches stripped WatchToast classes and crashes this app.
            // The servlet/listener/account-callback pieces are independent and
            // are required for MSF reconnect/account-change pushes.
            runCatching {
                val listenersField = MsfConnPushServlet::class.java
                    .getDeclaredField("b")
                    .apply { isAccessible = true }
                (listenersField.get(null) as? java.util.concurrent.CopyOnWriteArrayList<*>)?.clear()
            }.onFailure { Log.w(TAG, "bind: clear MSF push listeners failed", it) }
            val accountCallback = helper as? mqq.app.IAccountCallback
            if (accountCallback != null) {
                MobileQQ.sMobileQQ?.registerAccountCallback(accountCallback)
                Log.d(TAG, "bind: MSF helper account callback registered")
            } else {
                Log.w(TAG, "bind: MSF helper does not implement IAccountCallback: $helper")
            }

            helper.addPushListener(listener)
            val ownerRuntime = MobileQQ.sMobileQQ?.peekAppRuntime() ?: runtime
            ownerRuntime.startServlet(
                NewIntent(ownerRuntime.applicationContext, MsfConnPushServlet::class.java)
            )
            msfConnectionListener = listener
            msfConnectionBridgeRegistered = true
            Log.d(TAG, "bind: direct MSF push servlet registered helper=$helper runtime=$ownerRuntime")
        }.onFailure { error ->
            Log.w(TAG, "bind: direct MSF push servlet unavailable", error)
        }
    }


    private fun waitForSession(ks: IKernelService?, runtime: AppRuntime? = null) {
        var waitCount = 0
        while (waitCount < 5) {
            Thread.sleep(500)
            waitCount++
            val ws = runCatching { ks?.getWrapperSession() }.getOrNull()
            if (ws != null && runtime != null && RuntimeCoordinator.isCurrent(runtime)) {
                Log.d(TAG, "bind: kernel session established after ${waitCount * 500}ms")
                if (ks != null) cacheServices(ks, runtime, "waitForSession")
                replayForegroundToWrapperSession(ws)
                unblockPush()
                break
            }
        }
    }

    private fun replayForegroundToWrapperSession(session: Any) {
        val wrapperSession = session as? IQQNTWrapperSession
        if (wrapperSession == null) {
            Log.w(TAG, "bind: session foreground replay skipped; unexpected session=$session")
            return
        }
        if (foregroundReplayedSession === wrapperSession) {
            Log.d(TAG, "bind: session foreground replay already sent")
            return
        }
        val guardForeground = runCatching { GuardManager.c?.f() == true }
            .onFailure { Log.w(TAG, "bind: session foreground replay guard check failed", it) }
            .getOrDefault(false)
        val lifecycleForeground = runCatching { Foreground.isCurrentProcessForeground() }
            .onFailure { Log.w(TAG, "bind: session foreground replay lifecycle check failed", it) }
            .getOrDefault(false)
        if (!guardForeground && !lifecycleForeground) {
            Log.d(
                TAG,
                "bind: session foreground replay skipped; guard and lifecycle are background"
            )
            return
        }
        runCatching { wrapperSession.switchToFront() }
            .onSuccess {
                foregroundReplayedSession = wrapperSession
                Log.i(
                    TAG,
                    "bind: replayed foreground to WrapperSession " +
                            "guard=$guardForeground lifecycle=$lifecycleForeground"
                )
            }
            .onFailure { Log.w(TAG, "bind: WrapperSession.switchToFront failed", it) }
    }

    /** Reuse an already-created session without replaying the official injector path. */
    private fun initExistingKernel(runtime: AppRuntime?, ks: IKernelService) {
        if (runtime == null || !RuntimeCoordinator.isCurrent(runtime)) {
            Log.w(TAG, "initExistingKernel: runtime is no longer current")
            return
        }
        cacheServices(ks, runtime, "initExistingKernel")
        registerDirectMsfConnectionBridge(runtime)
        registerOfficialForegroundCallback(runtime)
        initializeOfficialMessageBridge(runtime)
        runCatching {
            val ctx = com.tencent.qphone.base.util.BaseApplication.getContext()
            ctx.sendBroadcast(
                android.content.Intent("com.tencent.mobileqq.action.ON_KERNEL_INIT_COMPLETE")
                    .setPackage(ctx.packageName),
            )
            Log.d(TAG, "initExistingKernel: ON_KERNEL_INIT_COMPLETE sent")
        }.onFailure { Log.w(TAG, "initExistingKernel: broadcast failed", it) }
        unblockPush()
    }

    private fun unblockPush() {
        runCatching {
            val msfServiceCls = Class.forName("com.tencent.mobileqq.msf.service.MsfService")
            val core =
                msfServiceCls.getDeclaredField("core").apply { isAccessible = true }.get(null)
            if (core != null) {
                val pm =
                    core.javaClass.getDeclaredField("pushManager").apply { isAccessible = true }
                        .get(core)
                if (pm != null) {
                    val oField = pm.javaClass.getDeclaredField("o")
                    oField.isAccessible = true
                    Log.d(TAG, "bind: PushManager.o before = ${oField.get(pm)}")
                    oField.set(pm, java.lang.Boolean.FALSE)
                    Log.d(TAG, "bind: PushManager.o set FALSE — push unblocked")
                }
            }
        }.onFailure { Log.e(TAG, "bind: unblock push failed", it) }
    }
}
