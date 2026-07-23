package rj.qmme.viewmodel

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.util.Log
import androidx.lifecycle.ViewModel
import com.tencent.qqnt.kernel.api.IKernelService
import com.tencent.qqnt.kernel.api.IMsgService
import com.tencent.qqnt.kernel.api.IRecentContactService
import com.tencent.qqnt.kernel.nativeinterface.AnchorPointContactInfo
import com.tencent.qqnt.kernel.nativeinterface.CompleteRecentContactInfo
import com.tencent.qqnt.kernel.nativeinterface.EnterOrExitMsgListInfo
import com.tencent.qqnt.kernel.nativeinterface.IKernelRecentSnapShotCallback
import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import com.tencent.qqnt.kernel.nativeinterface.RecentContactInfo
import com.tencent.qqnt.kernel.nativeinterface.RecentContactListChangedInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import mqq.app.AppRuntime
import rj.qmme.kernel.KernelBridge
import java.util.LinkedHashMap
import kotlin.Unit

/**
 * Native View/NT recent-contact pipeline.
 *
 * The important detail here is that getRecentContactListSnapShot() is not a
 * synchronous database read on this build.  The first native module may still
 * be coming up after KernelServiceImpl reports NT startup complete, and the
 * first Java RecentContactService can consequently wrap a null native delegate.
 * QMCE-Lite-X keeps polling for the live service and the snapshot for up to a
 * minute; this class follows that lifecycle without Compose dependencies.
 */
class ChatListViewModel : ViewModel() {
    companion object {
        private const val TAG = "QMME"
        private const val LIST_TYPE = 1
        private const val KERNEL_READY_TIMEOUT_MS = 60_000L
        private const val POLL_INTERVAL_MS = 500L
        private const val POLL_ATTEMPTS = 120 // 60 seconds
        private const val SERVICE_REFRESH_EVERY = 5
        private const val RETRY_DELAY_MS = 2_000L
        /** Refresh animation is only for dispatching a new native query, not for
         * the long-lived background polling that follows cold NT startup. */
        private const val REFRESH_INDICATOR_MIN_MS = 350L
        private const val REFRESH_SERVICE_LOOKUP_TIMEOUT_MS = 1_500L
        private const val MESSAGE_SERVICE_TIMEOUT_MS = 5_000L
    }

    data class ContactsSnapshot(
        val revision: Long,
        val contacts: List<RecentContactInfo>,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheLock = Any()
    private val contactsByKey = LinkedHashMap<Long, RecentContactInfo>()
    private var orderedKeys: List<Long> = emptyList()
    private var revision = 0L
    private var runtime: AppRuntime? = null
    private var recentService: IRecentContactService? = null
    private var recentV2Service: IRecentContactService? = null
    private var msgService: IMsgService? = null
    private var recentV2Listener: ((RecentContactListChangedInfo) -> Unit)? = null
    private var running = false
    private var loading = false
    private var initialLoadingShown = false
    private var retryJob: Job? = null
    private var refreshJob: Job? = null
    private var messageSyncJob: Job? = null

    private val _contacts = MutableStateFlow(ContactsSnapshot(0, emptyList()))
    val contacts: StateFlow<ContactsSnapshot> = _contacts

    private val _statusText = MutableStateFlow("正在连接 QQ 服务…")
    val statusText: StateFlow<String> = _statusText

    private val _isStatusVisible = MutableStateFlow(true)
    val isStatusVisible: StateFlow<Boolean> = _isStatusVisible

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    init {
        scope.launch {
            RecentMessageStore.version.collectLatest {
                publish("sent-message")
            }
        }
    }

    fun loadContacts(runtime: AppRuntime?) {
        if (runtime == null) {
            publishStatus("QQ Runtime 不可用")
            return
        }
        this.runtime = runtime
        running = true
        if (loading) {
            Log.d(TAG, "chatPoll: already loading, skip")
            return
        }
        loading = true
        scope.launch {
            try {
                var service = awaitRecentContactService(runtime)
                if (!running) return@launch
                if (service == null) {
                    publishStatus("会话服务未就绪，稍后重试")
                    scheduleRetry()
                    return@launch
                }
                recentService = service
                if (_contacts.value.contacts.isEmpty() && !initialLoadingShown) {
                    initialLoadingShown = true
                    publishStatus("正在加载会话…")
                }

                // Same order as qmce-lite-x: read local cache, install V2
                // listener, enter the message-list scope, then fetch a page.
                readAndPublishCache(service, "cached")
                registerRecentListener(service)
                enterMessageList(service)
                fetchRecentContacts(service)
                startMessageSync(runtime)

                // The native sync getter is useful once the module is live, but
                // it returns "service is nullptr" during early bootstrap.  Do
                // not interpret that transient error as an empty account.
                readAndPublishNativeSync(service, "native-sync")

                // Snapshot/fetch callbacks can arrive several seconds after
                // startNT.  Keep asking exactly as qmce does instead of showing
                // "暂无会话" after the first empty callback.
                pollRecentData(runtime, service)
            } catch (error: Throwable) {
                Log.e(TAG, "chat list load failed", error)
                publishStatus("会话加载失败，正在重试")
                scheduleRetry()
            } finally {
                loading = false
            }
        }
    }

    fun refreshContacts() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        refreshJob?.cancel()
        refreshJob = scope.launch {
            val startedAt = SystemClock.elapsedRealtime()
            try {
                // Pull-to-refresh must finish after a bounded query dispatch.
                // awaitRecentContactService() may poll during an NT cold start,
                // and keeping the indicator attached to that 60 s recovery loop
                // made a normal refresh appear permanently stuck.
                val current = recentService ?: withTimeoutOrNull(REFRESH_SERVICE_LOOKUP_TIMEOUT_MS) {
                    awaitRecentContactService(runtime)
                }
                if (current != null) {
                    recentService = current
                    registerRecentListener(current)
                    enterMessageList(current)
                    readAndPublishCache(current, "manual-refresh-cache")
                    readAndPublishNativeSync(current, "manual-refresh-native")
                    requestSnapshot(current, 0)
                    fetchRecentContacts(current)
                    startMessageSync(runtime)
                } else {
                    publishStatus("会话服务未就绪，正在后台重试")
                    if (!running) loadContacts(runtime) else scheduleRetry()
                }
            } finally {
                // Avoid a distracting flash, but never tie the visual spinner to
                // snapshot callbacks or the background 60-second polling job.
                val remaining = REFRESH_INDICATOR_MIN_MS -
                        (SystemClock.elapsedRealtime() - startedAt)
                if (remaining > 0L) delay(remaining)
                _isRefreshing.value = false
            }
        }
    }

    /** Preview text for the UI, including messages sent by this app as a fallback. */
    fun previewFor(contact: RecentContactInfo): String {
        val id = contact.id.orEmpty()
        val record = RecentMessageStore.latest(id)
        val sent = record?.elements
            ?.firstOrNull { it.elementType == 1 }
            ?.textElement
            ?.content
            ?.takeIf { it.isNotBlank() }
        return sent ?: extractNativePreview(contact) ?: "暂无消息"
    }

    private fun extractNativePreview(contact: RecentContactInfo): String? {
        // abstractContent is intentionally read reflectively. The current watch
        // runtime exposes it with a kernelpublic generic type that is absent from
        // the curated compile jar; erasure keeps the contact model usable.
        return runCatching {
            val list = contact.javaClass.getMethod("getAbstractContent").invoke(contact) as? List<*>
            list.orEmpty().asSequence().mapNotNull { element ->
                runCatching {
                    val content = element?.javaClass?.getMethod("getContent")?.invoke(element)
                        as? String
                    content?.takeIf { it.isNotBlank() }
                }.getOrNull()
            }.firstOrNull()
        }.getOrNull()
    }

    /**
     * Wait for both Java's NT gate and the actual native RecentContactService
     * delegate.  The latter matters: KernelServiceImpl may cache a Java service
     * whose BaseService.getService() is null while native modules are starting.
     */
    private suspend fun awaitRecentContactService(runtime: AppRuntime?): IRecentContactService? {
        if (runtime == null) return null
        val deadline = System.currentTimeMillis() + KERNEL_READY_TIMEOUT_MS
        var lastLogAt = 0L
        while (running && System.currentTimeMillis() < deadline) {
            val now = System.currentTimeMillis()
            val kernel = KernelBridge.getKernelService() ?: runCatching {
                runtime.getRuntimeService(IKernelService::class.java, "")
            }.getOrNull()

            // This refresh also clears a stale ClearableLazy created too early
            // by KernelServiceImpl.initService().
            KernelBridge.refreshCoreServices(runtime)
            val candidate = KernelBridge.getRecentContactService() ?: runCatching {
                kernel?.getRecentContactService()
            }.getOrNull()
            if (candidate != null && KernelBridge.isNativeServiceReady(candidate)) {
                Log.d(TAG, "chatPoll: live recentService=$candidate kernel=${kernelState(kernel)}")
                return candidate
            }

            if (now - lastLogAt >= 2_000L) {
                lastLogAt = now
                Log.d(
                    TAG,
                    "chatPoll: waiting live recent service; candidate=$candidate " +
                            "kernel=${kernelState(kernel)}",
                )
            }
            delay(250L)
        }
        return null
    }

    private fun kernelState(kernel: IKernelService?): String {
        if (kernel == null) return "ks=null"
        return runCatching {
            val impl = Class.forName("com.tencent.qqnt.kernel.api.impl.KernelServiceImpl")
            val ready = impl.getDeclaredField("isNTStartFinish").apply {
                isAccessible = true
            }.get(kernel)
            val value = ready.javaClass.getMethod("get").invoke(ready)
            val wrapper = impl.getDeclaredField("wrapperSession").apply {
                isAccessible = true
            }.get(kernel)
            "wrapper=${wrapper != null}, isNTStartFinish=$value"
        }.getOrElse { "stateError=${it.javaClass.simpleName}" }
    }

    private fun registerRecentListener(service: IRecentContactService) {
        if (recentV2Listener != null && recentV2Service === service) return

        val listener: (RecentContactListChangedInfo) -> Unit = { info ->
            Log.d(
                TAG,
                "recent listener v2: type=${info.notificationType}, " +
                        "changed=${info.changedList?.size}, " +
                        "sorted=${info.sortedContactList?.size}, listType=${info.listType}",
            )
            mainHandler.post {
                applyChange(info)
                publish("listener-v2")
            }
        }

        runCatching {
            invokeRecentOrThrow(service, listOf("q", "w"), LIST_TYPE, listener)
            recentV2Listener = listener
            recentV2Service = service
            Log.d(TAG, "recent contact V2 listener registered listType=$LIST_TYPE service=$service")
        }.onFailure { error ->
            Log.w(TAG, "recent contact V2 listener unavailable", error)
        }
    }

    private fun enterMessageList(service: IRecentContactService) {
        runCatching {
            service.enterOrExitMsgList(
                EnterOrExitMsgListInfo(7, 1),
                object : IOperateCallback {
                    override fun onResult(code: Int, errMsg: String?) {
                        Log.d(TAG, "enterOrExitMsgList enter code=$code msg=$errMsg")
                    }
                },
            )
        }.onFailure { Log.w(TAG, "enter message list failed", it) }
    }

    private fun readAndPublishCache(service: IRecentContactService, source: String) {
        val cached = readCachedContacts(service)
        Log.d(TAG, "recent cache read source=$source count=${cached.size}")
        if (cached.isNotEmpty()) {
            replaceCache(cached)
            publish(source)
        }
    }

    private fun readCachedContacts(service: IRecentContactService): List<RecentContactInfo> {
        return runCatching {
            // Watch ABI: D(listType).  qmce ABI: l(listType).  a() is the
            // list-type-1 compatibility getter in both builds.
            (invokeRecent(service, listOf("D", "l"), LIST_TYPE) as? List<RecentContactInfo>)
                ?: (invokeRecent(service, listOf("a")) as? List<RecentContactInfo>)
        }.onFailure { Log.w(TAG, "recent cache read failed", it) }
            .getOrNull()
            .orEmpty()
    }

    private fun requestSnapshot(service: IRecentContactService, attempt: Int) {
        runCatching {
            service.getRecentContactListSnapShot(
                LIST_TYPE,
                object : IKernelRecentSnapShotCallback {
                    override fun onResult(
                        code: Int,
                        errMsg: String?,
                        info: CompleteRecentContactInfo?,
                    ) {
                        Log.d(
                            TAG,
                            "recent snapshot[$attempt] code=$code msg=$errMsg " +
                                    "changed=${info?.changedList?.size} " +
                                    "sorted=${info?.sortedContactList?.size}",
                        )
                        if (code != 0 || info == null) return
                        val changed = info.changedList.orEmpty()
                        if (changed.isEmpty()) return
                        mainHandler.post {
                            replaceOrMerge(changed)
                            info.sortedContactList
                                ?.takeIf { it.isNotEmpty() }
                                ?.let { sorted -> synchronized(cacheLock) { orderedKeys = sorted } }
                            publish("snapshot-$attempt")
                        }
                    }
                },
            )
        }.onFailure { Log.w(TAG, "recent snapshot[$attempt] failed", it) }
    }

    private fun fetchRecentContacts(service: IRecentContactService) {
        runCatching {
            invokeRecentOrThrow(
                service,
                listOf("I", "v"),
                AnchorPointContactInfo(),
                true,
                LIST_TYPE,
                200,
                object : IOperateCallback {
                    override fun onResult(code: Int, errMsg: String?) {
                        Log.d(TAG, "fetch recent contacts code=$code msg=$errMsg")
                    }
                },
            )
            Log.d(TAG, "fetch recent contacts requested service=$service")
        }.onFailure { error ->
            Log.w(TAG, "fetch recent contacts failed", error)
        }
    }

    private suspend fun pollRecentData(
        runtime: AppRuntime?,
        initialService: IRecentContactService,
    ) {
        var service = initialService
        for (attempt in 0 until POLL_ATTEMPTS) {
            if (!running || _contacts.value.contacts.isNotEmpty()) return

            if (attempt > 0 && attempt % SERVICE_REFRESH_EVERY == 0) {
                val refreshed = currentLiveRecentService(runtime)
                if (refreshed != null && refreshed !== service) {
                    Log.i(TAG, "chatPoll: replacing stale recent service $service -> $refreshed")
                    service = refreshed
                    recentService = refreshed
                    registerRecentListener(refreshed)
                    enterMessageList(refreshed)
                    fetchRecentContacts(refreshed)
                }
            }

            readAndPublishCache(service, "poll-cache-$attempt")
            if (_contacts.value.contacts.isNotEmpty()) return

            if (attempt % SERVICE_REFRESH_EVERY == 0) {
                readAndPublishNativeSync(service, "poll-native-$attempt")
                if (_contacts.value.contacts.isNotEmpty()) return
            }

            requestSnapshot(service, attempt)
            if (attempt > 0 && attempt % SERVICE_REFRESH_EVERY == 0) {
                // A fetch with a null callback is what qmce uses for its
                // periodic retry; the callback overload above is equivalent,
                // but retaining the callback gives us useful diagnostics.
                fetchRecentContacts(service)
            }
            delay(POLL_INTERVAL_MS)
        }

        if (running && _contacts.value.contacts.isEmpty()) {
            Log.d(TAG, "chatPoll: 60s poll exhausted, no contacts")
            publishStatus("暂无会话")
            scheduleRetry()
        }
    }

    private fun currentLiveRecentService(runtime: AppRuntime?): IRecentContactService? {
        if (runtime == null) return null
        KernelBridge.refreshCoreServices(runtime)
        val kernel = KernelBridge.getKernelService() ?: runCatching {
            runtime.getRuntimeService(IKernelService::class.java, "")
        }.getOrNull()
        val candidate = KernelBridge.getRecentContactService() ?: runCatching {
            kernel?.getRecentContactService()
        }.getOrNull()
        return candidate?.takeIf { KernelBridge.isNativeServiceReady(it) }
    }

    private fun readAndPublishNativeSync(service: IRecentContactService, source: String) {
        val nativeSync = readNativeRecentSync(service) ?: return
        val nativeContacts = nativeSync.changedList.orEmpty()
        Log.d(
            TAG,
            "recent native sync source=$source code=${nativeSync.errCode} " +
                    "msg=${nativeSync.errMsg} changed=${nativeContacts.size} " +
                    "sorted=${nativeSync.sortedContactList?.size}",
        )
        if (nativeContacts.isEmpty()) return
        replaceCache(nativeContacts)
        nativeSync.sortedContactList
            ?.takeIf { it.isNotEmpty() }
            ?.let { sorted -> synchronized(cacheLock) { orderedKeys = sorted } }
        publish(source)
    }

    private fun readNativeRecentSync(service: IRecentContactService): CompleteRecentContactInfo? {
        if (!KernelBridge.isNativeServiceReady(service)) return null
        return runCatching {
            val nativeService = service.javaClass.methods.firstOrNull {
                it.name == "getService" && it.parameterTypes.isEmpty()
            }?.let { getter -> getter.invoke(service) } ?: findNativeDelegateField(service)
                ?: return@runCatching null
            val syncMethod = nativeService.javaClass.methods.firstOrNull {
                it.name == "getRecentContactListSync" && it.parameterTypes.isEmpty()
            } ?: error("native getRecentContactListSync() unavailable")
            syncMethod.invoke(nativeService) as? CompleteRecentContactInfo
        }.onFailure { Log.w(TAG, "recent native sync read failed", it) }
            .getOrNull()
    }

    private fun findNativeDelegateField(service: Any): Any? {
        var type: Class<*>? = service.javaClass
        while (type != null) {
            val field = type.declaredFields.firstOrNull {
                it.name == "service" || it.name == "nativeService" || it.name == "mService"
            }
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.get(service)
                }.getOrNull()
            }
            type = type.superclass
        }
        return null
    }

    private suspend fun startMessageSync(runtime: AppRuntime?) {
        val deadline = System.currentTimeMillis() + MESSAGE_SERVICE_TIMEOUT_MS
        var service: IMsgService? = null
        while (running && System.currentTimeMillis() < deadline) {
            KernelBridge.refreshCoreServices(runtime)
            service = KernelBridge.getMsgService()
                ?.takeIf { KernelBridge.isNativeServiceReady(it) }
            if (service != null) break
            delay(250L)
        }
        if (service == null) {
            Log.w(TAG, "recentSync: IMsgService unavailable after bootstrap wait")
            return
        }
        msgService = service
        runCatching {
            service.switchForeGround(object : IOperateCallback {
                override fun onResult(code: Int, errMsg: String?) {
                    Log.d(TAG, "switch foreground code=$code msg=$errMsg")
                }
            })
        }.onFailure { Log.d(TAG, "switch foreground skipped", it) }
        runCatching { service.startMsgSync() }
            .onFailure { Log.w(TAG, "initial startMsgSync failed", it) }

        if (messageSyncJob?.isActive != true) {
            messageSyncJob = scope.launch {
                while (running) {
                    delay(120_000L)
                    if (!running) break
                    runCatching { service.startMsgSync() }
                        .onSuccess { Log.d(TAG, "periodic startMsgSync") }
                        .onFailure { Log.w(TAG, "periodic startMsgSync failed", it) }
                }
            }
        }
    }

    private fun replaceCache(contacts: Collection<RecentContactInfo>) {
        synchronized(cacheLock) {
            contactsByKey.clear()
            contacts.forEach { contactsByKey[keyOf(it)] = it }
            orderedKeys = contacts
                .sortedWith(compareByDescending<RecentContactInfo> { it.sortField }
                    .thenByDescending { it.msgTime })
                .map(::keyOf)
        }
    }

    private fun replaceOrMerge(contacts: Collection<RecentContactInfo>) {
        if (contacts.isEmpty()) return
        synchronized(cacheLock) {
            contacts.forEach { incoming ->
                val key = keyOf(incoming)
                val previous = contactsByKey[key]
                // Some native notifications omit the abstract text.  Keep a
                // previous preview instead of replacing a valid row with a
                // visually empty one.
                if (previous != null && hasNoAbstract(incoming) && !hasNoAbstract(previous)) {
                    runCatching { incoming.abstractContent = previous.abstractContent }
                }
                contactsByKey[key] = incoming
            }
            if (orderedKeys.isEmpty()) {
                orderedKeys = contactsByKey.values
                    .sortedByDescending { it.msgTime }
                    .map(::keyOf)
            }
        }
    }

    private fun applyChange(info: RecentContactListChangedInfo) {
        if (info.notificationType == 1) {
            synchronized(cacheLock) {
                val previous = HashMap(contactsByKey)
                contactsByKey.clear()
                info.changedList.orEmpty().forEach { incoming ->
                    val old = previous[keyOf(incoming)]
                    if (old != null && hasNoAbstract(incoming) && !hasNoAbstract(old)) {
                        runCatching { incoming.abstractContent = old.abstractContent }
                    }
                    contactsByKey[keyOf(incoming)] = incoming
                }
                orderedKeys = info.sortedContactList.orEmpty().ifEmpty {
                    contactsByKey.values.sortedByDescending { it.msgTime }.map(::keyOf)
                }
            }
        } else {
            replaceOrMerge(info.changedList.orEmpty())
            info.sortedContactList.orEmpty().takeIf { it.isNotEmpty() }?.let { sorted ->
                synchronized(cacheLock) { orderedKeys = sorted }
            }
        }
    }

    private fun hasNoAbstract(contact: RecentContactInfo): Boolean {
        return runCatching {
            val list = contact.javaClass.getMethod("getAbstractContent").invoke(contact) as? List<*>
            list.orEmpty().none { element ->
                val content = runCatching {
                    element?.javaClass?.getMethod("getContent")?.invoke(element) as? String
                }.getOrNull()
                !content.isNullOrBlank()
            }
        }.getOrDefault(false)
    }

    private fun keyOf(contact: RecentContactInfo): Long {
        if (contact.contactId != 0L) return contact.contactId
        return contact.peerUid?.hashCode()?.toLong() ?: contact.id.orEmpty().hashCode().toLong()
    }

    private fun publish(source: String) {
        val visible = synchronized(cacheLock) {
            val result = ArrayList<RecentContactInfo>(contactsByKey.size)
            val emitted = HashSet<Long>()
            orderedKeys.forEach { key ->
                contactsByKey[key]?.let { result += it; emitted += key }
            }
            contactsByKey.entries
                .filter { it.key !in emitted }
                .map { it.value }
                .sortedWith(compareByDescending<RecentContactInfo> { it.sortField }
                    .thenByDescending { it.msgTime })
                .forEach(result::add)
            result
        }
        revision += 1
        _contacts.value = ContactsSnapshot(revision, visible)
        if (visible.isNotEmpty()) {
            setStatusVisible(false)
            // publishStatus("${visible.size} 条会话")
            retryJob?.cancel()
        }
        Log.d(TAG, "recent contacts published source=$source count=${visible.size}")
    }

    private fun publishStatus(value: String) {
        Log.d(TAG, "chat status=$value")
        mainHandler.post {
            _statusText.value = value
            setStatusVisible(true)
        }
    }

    private fun setStatusVisible(isVisible: Boolean) {
        Log.d(TAG, "chat status visible=$isVisible")
        mainHandler.post {
            _isStatusVisible.value = isVisible
        }
    }

    private fun scheduleRetry() {
        if (!running || retryJob?.isActive == true) return
        retryJob = scope.launch {
            delay(RETRY_DELAY_MS)
            retryJob = null
            if (running && _contacts.value.contacts.isEmpty()) loadContacts(runtime)
        }
    }

    private fun invokeRecent(
        service: IRecentContactService,
        names: List<String>,
        vararg args: Any?,
    ): Any? {
        val method = findRecentMethod(service, names, args) ?: return null
        return method.invoke(service, *args)
    }

    private fun invokeRecentOrThrow(
        service: IRecentContactService,
        names: List<String>,
        vararg args: Any?,
    ): Any? {
        val method = findRecentMethod(service, names, args)
            ?: error("recent contact method not found: $names/${args.size}")
        return method.invoke(service, *args)
    }

    /** Match both watch and qmce's obfuscated method names and erased types. */
    private fun findRecentMethod(
        service: IRecentContactService,
        names: List<String>,
        args: Array<out Any?>,
    ): java.lang.reflect.Method? {
        return service.javaClass.methods.firstOrNull { candidate ->
            candidate.name in names &&
                    candidate.parameterTypes.size == args.size &&
                    candidate.parameterTypes.zip(args).all { (type, arg) ->
                        isRecentArgumentCompatible(type, arg)
                    }
        }
    }

    private fun isRecentArgumentCompatible(type: Class<*>, arg: Any?): Boolean {
        if (arg == null) return !type.isPrimitive
        if (!type.isPrimitive) return type.isInstance(arg)
        return when (type) {
            Boolean::class.javaPrimitiveType -> arg is Boolean
            Byte::class.javaPrimitiveType -> arg is Byte
            Short::class.javaPrimitiveType -> arg is Short
            Int::class.javaPrimitiveType -> arg is Int
            Long::class.javaPrimitiveType -> arg is Long
            Float::class.javaPrimitiveType -> arg is Float
            Double::class.javaPrimitiveType -> arg is Double
            Char::class.javaPrimitiveType -> arg is Char
            else -> false
        }
    }

    override fun onCleared() {
        running = false
        refreshJob?.cancel()
        retryJob?.cancel()
        messageSyncJob?.cancel()
        scope.cancel()
        recentV2Listener?.let { listener ->
            recentV2Service?.let { service ->
                runCatching {
                    invokeRecent(service, listOf("y", "E"), LIST_TYPE, listener)
                    Log.d(TAG, "recent contact V2 listener removed")
                }.onFailure { Log.w(TAG, "remove recent V2 listener failed", it) }
            }
        }
        recentService?.let { service ->
            runCatching {
                service.enterOrExitMsgList(
                    EnterOrExitMsgListInfo(7, 2),
                    object : IOperateCallback {
                        override fun onResult(code: Int, errMsg: String?) = Unit
                    },
                )
            }.onFailure { Log.w(TAG, "exit message list failed", it) }
        }
        recentV2Listener = null
        recentV2Service = null
        recentService = null
        msgService = null
    }
}
