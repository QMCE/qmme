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
import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import com.tencent.qqnt.kernel.nativeinterface.IQQNTWrapperSession
import com.tencent.qqnt.msg.api.IMsgPushForegroundApi
import com.tencent.qqnt.watch.contact.api.IContactRuntimeService
import com.tencent.qqnt.watch.mainframe.api.IMsfConnHelper
import com.tencent.qqnt.watch.mainframe.servlet.MsfConnPushServlet
import com.tencent.qqnt.watch.selftab.api.ISelfProfileRuntimeService
import mqq.app.AppRuntime
import mqq.app.Foreground
import mqq.app.MobileQQ
import mqq.app.NewIntent
import rj.qmme.QmmeApp
import com.tencent.qqnt.kernel.api.impl.MsgService
import com.tencent.qqnt.kernel.api.impl.RecentContactService
import com.tencent.qqnt.kernel.api.impl.ServiceContent
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

    fun getKernelService(): IKernelService? = cachedKs
    fun getMsgService(): com.tencent.qqnt.kernel.api.IMsgService? = cachedMsgService
    fun getKernelMsgService(): IKernelMsgService? = runCatching {
        val kernelService = cachedKs ?: return@runCatching null
        val wrapperSession = kernelService.javaClass
            .getDeclaredField("wrapperSession")
            .apply { isAccessible = true }
            .get(kernelService) as? IQQNTWrapperSession
        wrapperSession?.msgService
    }.getOrNull()
    fun getRecentContactService(): com.tencent.qqnt.kernel.api.IRecentContactService? =
        cachedRecentService

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
        val kernelService = cachedKs ?: runCatching {
            runtime?.getRuntimeService(IKernelService::class.java, "")
        }.getOrNull()
        if (kernelService == null) return false
        cacheServices(kernelService)
        return cachedMsgService != null && cachedRecentService != null
    }

    /** True when a Java BaseService has a live native service behind it. */
    fun isNativeServiceReady(service: Any?): Boolean = hasNativeService(service)

    fun getBuddyService(): com.tencent.qqnt.kernel.api.IBuddyService? = cachedBuddyService
    fun getGroupService(): com.tencent.qqnt.kernel.api.IGroupService? = cachedGroupService
    fun ensureOfficialMessageBridge(
        runtimeOverride: AppRuntime? = null,
    ): com.tencent.qqnt.msg.api.IMsgService? {
        val runtime = runtimeOverride ?: QmmeApp.ensureRuntime()
        val kernelService = cachedKs ?: runCatching {
            runtime?.getRuntimeService(IKernelService::class.java, "")
        }.getOrNull()
        val kernelMsgService = cachedMsgService ?: runCatching {
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
            val kernelService = cachedKs ?: runCatching {
                runtime?.getRuntimeService(IKernelService::class.java, "")
            }.getOrNull()
            if (kernelService != null) {
                cacheServices(kernelService)
                if (cachedMsgService != null && cachedRecentService != null) {
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
            val kernelService = cachedKs ?: runCatching {
                runtime?.getRuntimeService(IKernelService::class.java, "")
            }.getOrNull()
            if (kernelService != null) {
                cacheServices(kernelService)
                cachedGroupService?.let { return it }
            }
            Thread.sleep(250)
        }
        Log.w(TAG, "KernelBridge: timed out waiting for group service")
        return cachedGroupService
    }

    /** bind 完成后由 waitForSession 调用，缓存各子 service.
     *
     * Keep this path identical to QMCE-Lite-X: only ask KernelServiceImpl for
     * its public service wrappers.  Calling the direct CppProxy service
     * getters here is not harmless; they cross the qmce/watch JNI boundary a
     * second time while the NTSdk thread is still finishing startup and were
     * the last operation before the stack-corruption abort.
     */
    private fun cacheServices(ks: IKernelService) = synchronized(serviceCacheLock) {
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
                    "state=${kernelState(ks)}"
        )
    }

    /** Return the direct wrapper session without forcing a KernelService lazy. */
    private fun wrapperSession(ks: IKernelService): IQQNTWrapperSession? {
        return runCatching {
            val impl = Class.forName("com.tencent.qqnt.kernel.api.impl.KernelServiceImpl")
            impl.getDeclaredField("wrapperSession").apply { isAccessible = true }
                .get(ks) as? IQQNTWrapperSession
        }.getOrNull()
    }

    /**
     * KernelServiceImpl can eagerly cache MsgService around a null native
     * pointer.  Build a fresh public service wrapper from the direct native
     * wrapper when that happens; ClearableLazy.clear() is destructive and must
     * not be used as a retry mechanism.
     */
    private fun liveMsgService(
        ks: IKernelService,
        candidate: com.tencent.qqnt.kernel.api.IMsgService?,
        native: IKernelMsgService?,
    ): com.tencent.qqnt.kernel.api.IMsgService? {
        if (candidate != null && hasNativeService(candidate)) {
            directMsgWrapper = candidate
            return candidate
        }
        if (native == null || !hasNativeHandle(native)) return null
        directMsgWrapper?.let { cached ->
            if (sameNativeDelegate(nativeDelegate(cached), native) && hasNativeService(cached)) return cached
        }
        return runCatching {
            val content = serviceContent(ks) ?: return@runCatching null
            MsgService(native, content).also { directMsgWrapper = it }
        }.onFailure {
            Log.w(TAG, "KernelBridge: direct MsgService wrapper failed", it)
        }.getOrNull()
    }

    private fun liveRecentService(
        ks: IKernelService,
        candidate: com.tencent.qqnt.kernel.api.IRecentContactService?,
        native: IKernelRecentContactService?,
    ): com.tencent.qqnt.kernel.api.IRecentContactService? {
        if (candidate != null && hasNativeService(candidate)) {
            directRecentWrapper = candidate
            return candidate
        }
        if (native == null || !hasNativeHandle(native)) return null
        directRecentWrapper?.let { cached ->
            if (sameNativeDelegate(nativeDelegate(cached), native) && hasNativeService(cached)) return cached
        }
        return runCatching {
            val content = serviceContent(ks) ?: return@runCatching null
            RecentContactService(native, content).also { directRecentWrapper = it }
        }.onFailure {
            Log.w(TAG, "KernelBridge: direct RecentContactService wrapper failed", it)
        }.getOrNull()
    }

    private fun serviceContent(ks: IKernelService): ServiceContent? {
        return runCatching {
            val impl = Class.forName("com.tencent.qqnt.kernel.api.impl.KernelServiceImpl")
            impl.getDeclaredField("serviceContent").apply { isAccessible = true }
                .get(ks) as? ServiceContent
        }.getOrNull()
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
            val runtime = QmmeApp.ensureRuntime(app)
            Log.d(
                TAG,
                "bind: runtime=$runtime, isLogin=${runtime?.isLogin()}, uin=${runtime?.currentUin}"
            )
            // AppRuntime.login(SimpleAccount) is not a local setter: it posts
            // MobileQQ.createNewRuntime(), which logs out/replaces the current
            // WatchAppInterface.  On a cold start the official runtime may already
            // be logged in with this exact account, so replaying login here causes a
            // runtime switch and can make MobileQQ terminate the process.  Only ask
            // MobileQQ to create a runtime when the account is actually different.
            val runtimeUin = runCatching { runtime?.currentUin }.getOrNull().orEmpty()
            val alreadyBound = runtime?.isLogin() == true && runtimeUin == uin
            if (runtime != null && !alreadyBound) {
                runCatching { runtime.login(account) }
                runCatching { runtime.setLogined() }
                Log.d(TAG, "bind: login requested for uin=$uin, oldUin=$runtimeUin")
            } else {
                Log.d(
                    TAG,
                    "bind: login skipped; same logged-in account=$alreadyBound " +
                            "runtimeUin=$runtimeUin requestedUin=$uin"
                )
            }
            Log.d(
                TAG,
                "bind: after account bind, isLogin=${runtime?.isLogin()}, uin=${runtime?.currentUin}"
            )

            injectSAccountModule()

            checkTicketStatus(runtime, uin)

            val ks = runCatching {
                runtime?.getRuntimeService(IKernelService::class.java, "")
            }.getOrNull()
            Log.d(TAG, "bind: kernelService=$ks")

            // createRuntime 里已经自动继承登录态，不用再调 waitAppRuntime 拿新实例
            val actualRuntime = runtime
            pinRuntime(actualRuntime)

            if (ks != null) {
                val existingSession = runCatching {
                    val f = ks.javaClass.getDeclaredField("wrapperSession"); f.isAccessible =
                    true; f.get(ks)
                }.getOrNull()
                Log.d(TAG, "bind: existingSession=$existingSession")
                if (existingSession == null) {
                    // pinRuntime 已在上面完成，先 patch serviceContent 再 start
                    patchServiceContent(ks, actualRuntime ?: runtime)
                    startKernelSession(ks, actualRuntime)
                } else {
                    Log.d(TAG, "bind: session already exists, initializing directly")
                    initExistingKernel(actualRuntime, ks)
                }
            }

            waitForSession(ks)
            val ready = reinitializeAfterLogin(actualRuntime)
            if (!ready) {
                Log.w(TAG, "bind: kernel session started but core services are not ready")
                "kernel services unavailable"
            } else {
                "ok"
            }
        }.getOrElse { "failed: ${it.javaClass.simpleName}: ${it.message}" }
    }

    fun reinitializeAfterLogin(runtime: AppRuntime?): Boolean {
        cachedKs = null
        cachedMsgService = null
        synchronized(officialMessageLock) {
            officialMessageKernel = null
            officialMessageService = null
        }
        cachedRecentService = null
        cachedBuddyService = null
        directMsgWrapper = null
        directRecentWrapper = null

        val coreReady = awaitCoreServices(runtimeOverride = runtime)
        if (!coreReady) {
            Log.w(TAG, "login reinitialize: core services unavailable")
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

        return cachedMsgService != null && cachedRecentService != null
    }

    @Volatile
    private var nativeKernelLibrariesLoaded = false


    /** 锁定 mAppRuntime 字段 + serviceContent 里的 runtime，
     *  防止 waitAppRuntime 创建新未初始化实例替换 */
    private fun pinRuntime(runtime: AppRuntime?) {
        if (runtime == null) return

        // createRuntime() may be called by MobileQQ while login callbacks are running.
        // Keep the runtime that owns the KernelService we are about to start as the
        // application-wide source of truth as well.
        QmmeApp.sAppRuntime = runtime

        // 1. MobileQQ.mAppRuntime
        runCatching {
            val f = MobileQQ::class.java.getDeclaredField("mAppRuntime")
            f.isAccessible = true
            val current = f.get(MobileQQ.sMobileQQ)
            if (current !== runtime) {
                f.set(MobileQQ.sMobileQQ, runtime)
                Log.d(TAG, "bind: pinned mAppRuntime: $current -> $runtime")
            }
            val stateField = MobileQQ::class.java.getDeclaredField("mRuntimeState")
            stateField.isAccessible = true
            (stateField.get(MobileQQ.sMobileQQ) as? java.util.concurrent.atomic.AtomicInteger)?.set(
                3
            )
        }.onFailure { Log.e(TAG, "bind: pinRuntime mAppRuntime failed", it) }

        // 2. KernelServiceImpl.serviceContent 里的 WeakReference<AppRuntime>
        runCatching {
            val ks =
                runtime?.getRuntimeService(IKernelService::class.java, "") ?: return@runCatching
            val ksImplCls = Class.forName("com.tencent.qqnt.kernel.api.impl.KernelServiceImpl")
            val scField = ksImplCls.getDeclaredField("serviceContent"); scField.isAccessible = true
            val sc = scField.get(ks)
            if (sc != null) {
                val aField = sc.javaClass.getDeclaredField("a"); aField.isAccessible = true
                val weakRef = aField.get(sc)
                if (weakRef != null) {
                    setWeakRefReferent(weakRef, runtime)
                    Log.d(TAG, "bind: pinned serviceContent runtime -> $runtime")
                }
            }
        }.onFailure { Log.e(TAG, "bind: pinRuntime serviceContent failed", it) }
    }

    /** 在 ks.start() 之前 patch serviceContent WeakReference，
     *  确保 native startServlet 拿到已初始化的 runtime */
    private fun patchServiceContent(ks: IKernelService, runtime: AppRuntime?) {
        runCatching {
            val ksImplCls = Class.forName("com.tencent.qqnt.kernel.api.impl.KernelServiceImpl")
            val scField = ksImplCls.getDeclaredField("serviceContent"); scField.isAccessible = true
            val sc = scField.get(ks) ?: return@runCatching
            val aField = sc.javaClass.getDeclaredField("a"); aField.isAccessible = true
            val weakRef = aField.get(sc) ?: return@runCatching
            setWeakRefReferent(weakRef, runtime)
            Log.d(
                TAG,
                "patchServiceContent: set runtime=$runtime, isLogin=${runtime?.isLogin()}, isRunning=${runtime?.isRunning}"
            )
        }.onFailure { Log.e(TAG, "patchServiceContent failed", it) }
    }

    /** ART 上 WeakReference 继承 Object，referent 在 ART 内部类中，需要遍历所有字段 */
    private fun setWeakRefReferent(weakRef: Any, value: Any?) {
        var cls: Class<*>? = weakRef.javaClass
        while (cls != null) {
            val fields = runCatching { cls.declaredFields }.getOrNull()
            if (fields != null) {
                for (f in fields) {
                    if (f.name == "referent" || f.type == AppRuntime::class.java || f.type == Any::class.java) {
                        f.isAccessible = true
                        val current = f.get(weakRef)
                        if (current != null && current is AppRuntime) {
                            if (current !== value) {
                                f.set(weakRef, value)
                                Log.d(
                                    TAG,
                                    "setWeakRefReferent: patched field '${f.name}' in ${cls.simpleName}"
                                )
                            }
                            return
                        }
                    }
                }
            }
            cls = cls.superclass
        }
        Log.w(TAG, "setWeakRefReferent: could not find referent field")
    }

    private fun injectSAccountModule() {
        runCatching {
            val ksImplCls = Class.forName("com.tencent.qqnt.kernel.api.impl.KernelServiceImpl")
            val sAccountModuleField = ksImplCls.getDeclaredField("sAccountModule")
            sAccountModuleField.isAccessible = true
            if (sAccountModuleField.get(null) == null) {
                val accountModuleCls =
                    Class.forName("com.tencent.qqnt.watch.inject.AccountModuleInjector")
                val accountModule = accountModuleCls.getDeclaredConstructor().newInstance()
                sAccountModuleField.set(null, accountModule)
                Log.d(TAG, "bind: sAccountModule set to $accountModule")
            }
        }.onFailure { Log.e(TAG, "bind: set sAccountModule failed", it) }
    }

    private fun checkTicketStatus(runtime: AppRuntime?, uin: String) {
        runCatching {
            val ticketClass =
                Class.forName("com.tencent.qqnt.account.login.api.ITicketRuntimeService")
            val m = runtime?.javaClass?.methods?.firstOrNull {
                it.name == "getRuntimeService" && it.parameterTypes.size == 2
            }
            val ticketSvc = m?.invoke(runtime, ticketClass, "")
            Log.d(TAG, "bind: ticketSvc=$ticketSvc")
            if (ticketSvc != null) {
                val a2 = runCatching {
                    ticketSvc.javaClass.getMethod("getA2", String::class.java)
                        .invoke(ticketSvc, uin)
                }.getOrNull()
                Log.d(TAG, "bind: A2=$a2")
                val localTicket = runCatching {
                    ticketSvc.javaClass.getMethod(
                        "getLocalTicket",
                        String::class.java,
                        Int::class.javaPrimitiveType
                    )
                        .invoke(ticketSvc, uin, 262144)
                }.getOrNull()
                Log.d(TAG, "bind: localTicket=$localTicket")
            }
        }.onFailure { Log.e(TAG, "bind: ticket check failed", it) }
    }

    /**
     * Starts the already-created KernelService without instantiating KernelSetterImpl.
     *
     * KernelSetterImpl's static initializer owns the original QQ InitialModule loader.
     * That loader is tied to the watch APK's native namespace and eagerly loads the
     * whole wrapper/startup stack.  In this app it can terminate the process from a
     * static initializer when the exported wrapper symbol is not visible to
     * libstartup.so.  KernelServiceImpl.start() is the actual session entry point and
     * already initializes its own AppSetting/Business/Sender modules in its
     * constructor, so the setter is not needed here.
     */
    /**
     * Start the official QQ Watch kernel path.  The Watch Java API and
     * libwrapper/libgprowrapper are one ABI; bypassing KernelSetterImpl and
     * calling qmce's IQQNTWrapperSession.CppProxy.startNT() is not compatible
     * and is the direct cause of the native stack-corruption abort.
     */
    private fun startKernelSession(ks: IKernelService, runtime: AppRuntime?) {
        val boundRuntime = runtime ?: run {
            Log.e(TAG, "bind: cannot start kernel session without runtime")
            return
        }
        val setter = runCatching {
            val setterClass = Class.forName("com.tencent.qqnt.kernel.api.impl.KernelSetterImpl")
            setterClass.getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        }.onFailure { Log.e(TAG, "bind: create official KernelSetterImpl failed", it) }
            .getOrNull() ?: return

        runCatching {
            val appRef = setter.javaClass.getDeclaredField("mAppRef")
            appRef.isAccessible = true
            val weakReferenceClass = Class.forName("mqq.util.WeakReference")
            appRef.set(
                setter,
                weakReferenceClass.getDeclaredConstructor(Any::class.java)
                    .newInstance(boundRuntime),
            )
        }.onFailure { Log.w(TAG, "bind: set KernelSetterImpl.mAppRef failed", it) }

        runCatching {
            setter.javaClass.getMethod("ensureInject").invoke(setter)
        }.onFailure { Log.w(TAG, "bind: official KernelSetterImpl.ensureInject failed", it) }

        // The original setter reads this injector when constructing its native
        // session.  Inject it explicitly because the curated application does
        // not run the original QQ startup task graph.
        runCatching {
            val setterClass = Class.forName("com.tencent.qqnt.kernel.api.impl.KernelSetterImpl")
            val appSettingField = setterClass.getDeclaredField("sAppSetting")
            appSettingField.isAccessible = true
            if (appSettingField.get(null) == null) {
                val injector = Class.forName("com.tencent.qqnt.watch.inject.AppSettingInjector")
                    .getDeclaredConstructor()
                    .newInstance()
                appSettingField.set(null, injector)
                Log.d(TAG, "bind: official AppSettingInjector injected=$injector")
            }
        }.onFailure { Log.w(TAG, "bind: official AppSettingInjector injection failed", it) }

        kernelInitCompleteNotified.set(false)
        val listener = java.lang.reflect.Proxy.newProxyInstance(
            Class.forName("com.tencent.qqnt.kernel.api.IKernelCreateListener").classLoader,
            arrayOf(Class.forName("com.tencent.qqnt.kernel.api.IKernelCreateListener")),
        ) { _, method, _ ->
            when (method.name) {
                "a" -> {
                    Log.d(TAG, "IKernelCreateListener.a called (official kernel created)")
                    runCatching {
                        setter.javaClass.getMethod("setServletKernelInit").invoke(setter)
                        Log.d(TAG, "official setServletKernelInit OK")
                    }.onFailure { Log.w(TAG, "official setServletKernelInit failed", it) }
                    null
                }

                "b" -> {
                    if (!kernelInitCompleteNotified.compareAndSet(false, true)) {
                        Log.d(TAG, "IKernelCreateListener.b ignored (already completed)")
                        null
                    } else {
                        Log.d(TAG, "IKernelCreateListener.b called (official kernel init complete)")
                        registerDirectMsfConnectionBridge(boundRuntime)
                        registerOfficialForegroundCallback(boundRuntime)
                        initializeOfficialMessageBridge(boundRuntime)
                        Log.d(TAG, "kernel init: skip incompatible contact refresh")
                        runCatching {
                            val context = com.tencent.qphone.base.util.BaseApplication.getContext()
                            context.sendBroadcast(
                                android.content.Intent("com.tencent.mobileqq.action.ON_KERNEL_INIT_COMPLETE")
                                    .setPackage(context.packageName),
                            )
                            Log.d(TAG, "ON_KERNEL_INIT_COMPLETE broadcast sent")
                        }.onFailure { Log.w(TAG, "sendBroadcast failed", it) }
                        null
                    }
                }

                "hashCode" -> 42
                "equals" -> false
                "toString" -> "QMME-OfficialKernelCreateListener"
                else -> null
            }
        }

        runCatching {
            val callbackMethod = setter.javaClass.getMethod(
                "getAccountCallback",
                Class.forName("com.tencent.qqnt.kernel.api.IKernelCreateListener"),
            )
            val accountCallback = callbackMethod.invoke(setter, listener)
            MobileQQ.sMobileQQ?.registerAccountCallback(accountCallback as? mqq.app.IAccountCallback)
            Log.d(TAG, "bind: official account callback registered=$accountCallback")
        }.onFailure { Log.w(TAG, "bind: official account callback setup failed", it) }

        runCatching {
            ks.start(listener as com.tencent.qqnt.kernel.api.IKernelCreateListener)
            Log.i(TAG, "bind: official KernelServiceImpl.start(listener) requested")
        }.onFailure { Log.e(TAG, "bind: official KernelServiceImpl.start failed", it) }
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


    private fun waitForSession(ks: IKernelService?) {
        var waitCount = 0
        while (waitCount < 5) {
            Thread.sleep(500)
            waitCount++
            val ws = runCatching {
                val f = ks?.javaClass?.getDeclaredField("wrapperSession"); f?.isAccessible =
                true; f?.get(ks)
            }.getOrNull()
            if (ws != null) {
                Log.d(TAG, "bind: kernel session established after ${waitCount * 500}ms")
                if (ks != null) cacheServices(ks)
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

    /** 复用已有 wrapperSession 时，补做 IKernelCreateListener 回调里的关键初始化 */
    private fun initExistingKernel(runtime: AppRuntime?, ks: IKernelService) {
        Thread.sleep(500)
        // A reused service does not pass through the direct starter's create
        // callback, so install the safe MSF bridge here as well.
        registerDirectMsfConnectionBridge(runtime)
        cacheServices(ks)
        runCatching {
            val contactSvc = runtime?.getRuntimeService(IContactRuntimeService::class.java, "")
            Log.d(TAG, "initExistingKernel: contactSvc=$contactSvc")
            contactSvc?.initUinToUidCache(true)
            Log.d(TAG, "initExistingKernel: initUinToUidCache(true) OK")
        }.onFailure { Log.e(TAG, "initExistingKernel: initUinToUidCache failed", it) }

        runCatching {
            val buddySvc = ks.getBuddyService()
            Log.d(TAG, "initExistingKernel: buddySvc=$buddySvc")
            buddySvc?.getBuddyList(true, object : IOperateCallback {
                override fun onResult(code: Int, errMsg: String?) {
                    Log.d(TAG, "initExistingKernel: getBuddyList code=$code, errMsg=$errMsg")
                }
            })
            Log.d(TAG, "initExistingKernel: getBuddyList(true) called")
        }.onFailure { Log.e(TAG, "initExistingKernel: getBuddyList failed", it) }

        runCatching {
            val ctx = com.tencent.qphone.base.util.BaseApplication.getContext()
            ctx.sendBroadcast(
                android.content.Intent("com.tencent.mobileqq.action.ON_KERNEL_INIT_COMPLETE")
                    .setPackage(ctx.packageName)
            )
            Log.d(TAG, "initExistingKernel: ON_KERNEL_INIT_COMPLETE sent")
        }.onFailure { Log.e(TAG, "initExistingKernel: broadcast failed", it) }

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
