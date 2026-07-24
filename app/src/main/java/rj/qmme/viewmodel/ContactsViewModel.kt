package rj.qmme.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tencent.qqnt.kernel.api.IBuddyService
import com.tencent.qqnt.kernel.api.IKernelService
import com.tencent.qqnt.kernel.nativeinterface.BuddyListCategory
import com.tencent.qqnt.kernel.nativeinterface.BuddyListReqType
import com.tencent.qqnt.kernel.nativeinterface.IBuddyListCallback
import com.tencent.qqnt.kernel.nativeinterface.RecentContactInfo
import com.tencent.qqnt.kernel.nativeinterface.UserSimpleInfo
import com.tencent.qqnt.watch.contact.api.IContactRuntimeService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import mqq.app.AppRuntime
import rj.qmme.kernel.KernelBridge
import rj.qmme.runtime.RuntimeCoordinator
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Native contact data pipeline migrated from qmce-lite-x.
 *
 * The watch 9.0.7 SDK changed IBuddyService#getBuddyListV2 to:
 *   getBuddyListV2(callFrom, force, requestType, callback)
 *
 * The old qmce implementation used the previous three-argument signature, so the
 * old code compiled against qq-sdk.jar but could not be used with this project's
 * current qq-core.jar.  This implementation follows the same official flow while
 * using the current signature and ContactRuntimeService as a fallback.
 */
class ContactsViewModel : ViewModel() {
    companion object {
        private const val TAG = "QMME-Contacts"
        private const val RETRY_DELAY_MS = 2_000L
        private const val SERVICE_WAIT_MS = 15_000L
        // The native buddy service may take longer to warm up. Keep that work
        // in the background, but do not hold pull-to-refresh captive to it.
        private const val REFRESH_INDICATOR_MAX_MS = 1_500L
    }

    data class UiBuddy(
        val uid: String,
        val uin: Long,
        val nick: String,
        val remark: String,
        val avatarPath: String,
        val avatarUrls: List<String>,
        val categoryId: Int,
        val categoryName: String,
    )

    data class UiCategory(
        val id: Int,
        val name: String,
        val buddies: List<UiBuddy>,
    )

    private val _categories = MutableStateFlow<List<UiCategory>>(emptyList())
    val categories: StateFlow<List<UiCategory>> = _categories

    private val _statusText = MutableStateFlow("")
    val statusText: StateFlow<String> = _statusText

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    @Volatile
    private var loaded = false
    private var retryJob: Job? = null
    private var loadJob: Job? = null
    private var refreshIndicatorJob: Job? = null
    private val loadGeneration = AtomicInteger()
    private val loadLock = Any()

    fun loadBuddies(
        runtime: AppRuntime?,
        forceRefresh: Boolean = false,
        userInitiated: Boolean = false,
    ) {
        if (loaded && !forceRefresh) return
        synchronized(loadLock) {
            if (loadJob?.isActive == true) {
                // A user explicitly asked for a fresh snapshot. The previous
                // job may only be doing slow avatar/UIN enrichment, so replace
                // it instead of leaving the SwipeRefresh indicator orphaned.
                if (!forceRefresh) return
                loadJob?.cancel()
            }
            if (forceRefresh) loaded = false
            _loading.value = true
            _statusText.value = if (userInitiated) "正在刷新联系人…" else "加载联系人…"
            val generation = loadGeneration.incrementAndGet()
            if (userInitiated) startRefreshIndicator(generation)
            loadJob = viewModelScope.launch(Dispatchers.IO) {
                try {
                    val actualRuntime = runtime
                        ?: RuntimeCoordinator.currentRuntime()
                        ?: rj.qmme.QmmeApp.ensureRuntime()
                    if (actualRuntime != null) {
                        RuntimeCoordinator.observeRuntime(
                            actualRuntime,
                            source = "ContactsViewModel.loadBuddies",
                        )
                    }
                    val result = awaitBuddyService(actualRuntime)
                    if (result == null) {
                        if (generation == loadGeneration.get()) {
                            loaded = false
                            _statusText.value = "联系人服务未就绪，正在重试"
                            scheduleRetry(actualRuntime, "buddy-service-unavailable")
                        }
                        return@launch
                    }

                    val (buddyService, contactService) = result
                    // Re-read public kernel wrappers after the buddy service is
                    // observable. Profile/UIN enrichment can otherwise race the
                    // lazy service cache on a cold watch start.
                    runCatching { KernelBridge.refreshCoreServices(actualRuntime) }
                        .onFailure { Log.d(TAG, "refresh kernel services before contacts failed", it) }
                    val categories = loadFromOfficialContactPipeline(
                        buddyService = buddyService,
                        contactService = contactService,
                        runtime = actualRuntime,
                        forceRefresh = forceRefresh,
                        generation = generation,
                    )
                    if (categories.isNotEmpty()) {
                        if (generation == loadGeneration.get()) {
                            loaded = true
                            _categories.value = categories
                            _statusText.value = "${categories.sumOf { it.buddies.size }} 位联系人"
                            retryJob?.cancel()
                        }
                        Log.d(
                            TAG,
                            "contacts published categories=${categories.size} " +
                                    "buddies=${categories.sumOf { it.buddies.size }}",
                        )
                    } else {
                        // An empty response can mean the native buddy cache has not
                        // completed yet. Keep qmce's retry behavior instead of
                        // permanently rendering an empty page.
                        if (generation == loadGeneration.get()) {
                            loaded = false
                            _statusText.value = "联系人服务暂未返回数据，正在重试"
                            scheduleRetry(actualRuntime, "empty-contact-result")
                        }
                    }
                } catch (cancelled: CancellationException) {
                    // Cancellation is the expected path when a newer manual
                    // refresh supersedes background enrichment.
                    Log.d(TAG, "contact load cancelled generation=$generation")
                    throw cancelled
                } catch (error: Throwable) {
                    if (generation == loadGeneration.get()) {
                        loaded = false
                        _statusText.value = "联系人加载失败，正在重试"
                        scheduleRetry(runtime, "${error.javaClass.simpleName}")
                    }
                    Log.e(TAG, "contact load failed", error)
                } finally {
                    if (generation == loadGeneration.get()) {
                        _loading.value = false
                        stopRefreshIndicator(generation)
                        synchronized(loadLock) {
                            if (generation == loadGeneration.get()) loadJob = null
                        }
                    }
                }
            }
        }
    }

    fun refresh(runtime: AppRuntime? = null) {
        loadBuddies(
            runtime = runtime ?: RuntimeCoordinator.currentRuntime(),
            forceRefresh = true,
            userInitiated = true,
        )
    }

    private fun startRefreshIndicator(generation: Int) {
        refreshIndicatorJob?.cancel()
        _isRefreshing.value = true
        refreshIndicatorJob = viewModelScope.launch {
            delay(REFRESH_INDICATOR_MAX_MS)
            if (generation == loadGeneration.get()) {
                _isRefreshing.value = false
            }
        }
    }

    private fun stopRefreshIndicator(generation: Int) {
        if (generation != loadGeneration.get()) return
        refreshIndicatorJob?.cancel()
        refreshIndicatorJob = null
        _isRefreshing.value = false
    }

    private suspend fun awaitBuddyService(
        runtime: AppRuntime?,
    ): Pair<IBuddyService, IContactRuntimeService?>? {
        val deadline = System.currentTimeMillis() + SERVICE_WAIT_MS
        var buddy = KernelBridge.getBuddyService()
        var contact: IContactRuntimeService? = getContactRuntimeService(runtime)
        while (buddy == null && System.currentTimeMillis() < deadline) {
            delay(250)
            val kernel = KernelBridge.getKernelService() ?: runCatching {
                runtime?.getRuntimeService(IKernelService::class.java, "")
            }.getOrNull()
            if (kernel != null) {
                buddy = runCatching { kernel.getBuddyService() }.getOrNull()
                contact = contact ?: getContactRuntimeService(runtime)
            }
        }
        return buddy?.let { it to contact }
    }

    private fun getContactRuntimeService(runtime: AppRuntime?): IContactRuntimeService? {
        return runCatching {
            runtime?.getRuntimeService(IContactRuntimeService::class.java, "")
        }.getOrNull()
    }

    /**
     * Same data path as qmce-lite-x's ContactsViewModel:
     * BuddyService -> BuddyListCategory -> profile/nick/remark/UIN enrichment.
     *
     * The previous implementation deliberately returned an empty list here,
     * which made the Contacts page look like a Kernel failure even though the
     * service was already available.  The exact QQNT model classes now live in
     * qq-core-watch-runtime.jar, so use the official V2 callback path again.
     */
    private suspend fun loadFromOfficialContactPipeline(
        buddyService: IBuddyService,
        contactService: IContactRuntimeService?,
        runtime: AppRuntime?,
        forceRefresh: Boolean,
        generation: Int,
    ): List<UiCategory> {
        runCatching { contactService?.initUinToUidCache(forceRefresh) }
            .onFailure { Log.w(TAG, "initUinToUidCache failed", it) }

        val categories = requestBuddyCategories(buddyService, forceRefresh)
            .getOrElse { error ->
                Log.e(TAG, "getBuddyListV2 failed", error)
                return emptyList()
            }
        if (categories.isEmpty()) {
            return loadFromContactRuntime(contactService, forceRefresh)
        }

        val allUids = categories
            .flatMap { it.buddyUids.orEmpty() }
            .filter { it.isNotBlank() }
            .distinct()
        if (allUids.isEmpty()) return loadFromContactRuntime(contactService, forceRefresh)

        val nickMap: Map<String, String> = runCatching {
            buddyService.getBuddyNick(ArrayList(allUids))
        }.onFailure { Log.w(TAG, "getBuddyNick failed", it) }
            .getOrNull() ?: emptyMap()
        val remarkMap: Map<String, String> = runCatching {
            buddyService.getBuddyRemark(ArrayList(allUids))
        }.onFailure { Log.w(TAG, "getBuddyRemark failed", it) }
            .getOrNull() ?: emptyMap()

        val kernelService = KernelBridge.getKernelService() ?: runCatching {
            runtime?.getRuntimeService(IKernelService::class.java, "")
        }.getOrNull()
        val profileService = kernelService?.getProfileService()
        val profiles: Map<String, UserSimpleInfo> = runCatching {
            profileService?.getCoreAndBaseInfo("ContactRepo", ArrayList(allUids))
        }.onFailure { Log.w(TAG, "getCoreAndBaseInfo failed", it) }
            .getOrNull() ?: emptyMap()
        val uins = LinkedHashMap<String, Long>()
        val profileUins: Map<String, Long> = runCatching {
            profileService?.getUinByUid("ContactRepo", ArrayList(allUids))
        }.onFailure { Log.w(TAG, "profile getUinByUid failed", it) }
            .getOrNull() ?: emptyMap()
        profileUins.forEach { (uid, uin) ->
                if (uin > 0L) uins[uid] = uin
            }

        var current = buildCategories(categories, profiles, nickMap, remarkMap, uins)
        if (generation == loadGeneration.get()) {
            _categories.value = current
            _statusText.value = ""
            // Publish the first complete category snapshot immediately. UIN and
            // avatar enrichment continues in the background, as in qmce-lite-x.
            _loading.value = false
            stopRefreshIndicator(generation)
        }
        Log.d(
            TAG,
            "contacts categories received categories=${categories.size} " +
                    "uids=${allUids.size} resolvedUins=${uins.size}",
        )

        // UIN/avatar information may become available a little later than the
        // category callback. Match qmce-lite-x and publish progressively rather
        // than holding the whole contact page behind the enrichment loop.
        for (attempt in 0 until 60) {
            if (generation != loadGeneration.get()) break
            allUids.forEach { uid ->
                if (uid !in uins) {
                    contactService?.getUinByUid(uid)
                        ?.takeIf { it > 0L }
                        ?.let { uins[uid] = it }
                }
            }
            val recentByUid: Map<String?, RecentContactInfo> =
                readRecentContacts()
                    .associateBy { it.peerUid }
            val next = buildCategories(categories, profiles, nickMap, remarkMap, uins, recentByUid)
            if (next != current) {
                current = next
                if (generation == loadGeneration.get()) {
                    _categories.value = current
                }
                Log.d(
                    TAG,
                    "contacts enriched attempt=$attempt " +
                            "resolvedUins=${uins.size}/${allUids.size}",
                )
            }
            if (uins.size >= allUids.size) break
            delay(500)
        }
        return current
    }

    /**
     * The Watch and qmce runtimes expose the same native operation under
     * different obfuscated JVM names. Kotlin metadata on the copied QQNT
     * interface is not stable across the two artifacts, so use the same erased
     * ABI lookup as ChatListViewModel instead of binding to a source-only name.
     */
    private fun readRecentContacts(): List<RecentContactInfo> {
        val service = KernelBridge.getRecentContactService() ?: return emptyList()
        val method = service.javaClass.methods.firstOrNull { candidate ->
            (candidate.name == "D" || candidate.name == "l") &&
                    candidate.parameterTypes.size == 1 &&
                    candidate.parameterTypes[0] == Int::class.javaPrimitiveType
        } ?: service.javaClass.methods.firstOrNull { candidate ->
            candidate.name == "a" && candidate.parameterTypes.isEmpty()
        } ?: return emptyList()
        return runCatching {
            @Suppress("UNCHECKED_CAST")
            val result = if (method.parameterTypes.isEmpty()) {
                method.invoke(service)
            } else {
                method.invoke(service, 1)
            }
            (result as? List<*>)?.filterIsInstance<RecentContactInfo>().orEmpty()
        }.onFailure { Log.w(TAG, "recent contact cache read failed", it) }
            .getOrDefault(emptyList())
    }

    private suspend fun loadFromContactRuntime(
        contactService: IContactRuntimeService?,
        forceRefresh: Boolean,
    ): List<UiCategory> {
        if (contactService == null) return emptyList()
        val users = runCatching {
            contactService.initUinToUidCache(forceRefresh)
            contactService.getContactList(forceRefresh).first()
        }.onFailure { Log.w(TAG, "ContactRuntimeService.getContactList failed", it) }
            .getOrNull().orEmpty()
        if (users.isEmpty()) return emptyList()

        val grouped = users
            .filter { it.uid.isNotBlank() }
            .groupBy { it.baseInfo?.categoryId ?: 0 }
        return grouped.entries
            .sortedBy { it.key }
            .map { (categoryId, entries) ->
                UiCategory(
                    id = categoryId,
                    name = if (categoryId == 0) "我的好友" else "分组 $categoryId",
                    buddies = entries.map { info ->
                        val core = info.coreInfo
                        val uin = info.uin.takeIf { it > 0L }
                            ?: contactService.getUinByUid(info.uid).orZero()
                        toUiBuddy(
                            uid = info.uid,
                            uin = uin,
                            nick = core?.nick,
                            remark = core?.remark,
                            avatarPath = "",
                            categoryId = categoryId,
                            categoryName = if (categoryId == 0) "我的好友" else "分组 $categoryId",
                        )
                    },
                )
            }
    }

    private suspend fun requestBuddyCategories(
        service: IBuddyService,
        forceRefresh: Boolean,
    ): Result<List<BuddyListCategory>> = suspendCancellableCoroutine { continuation ->
        val completed = AtomicBoolean(false)
        fun complete(result: Result<List<BuddyListCategory>>) {
            if (completed.compareAndSet(false, true) && continuation.isActive) {
                continuation.resume(result)
            }
        }
        runCatching {
            // The current QQ Watch SDK requires the callFrom argument.  The
            // official ContactRuntimeService and ContactRepo both pass "".
            service.getBuddyListV2(
                "",
                forceRefresh,
                BuddyListReqType.KNOMAL,
                object : IBuddyListCallback {
                    override fun onResult(
                        code: Int,
                        errMsg: String?,
                        list: java.util.ArrayList<BuddyListCategory>?,
                    ) {
                        Log.d(TAG, "getBuddyListV2 code=$code msg=$errMsg count=${list?.size}")
                        if (code == 0 && list != null) {
                            complete(Result.success(list))
                        } else {
                            complete(Result.failure(IllegalStateException("$code: $errMsg")))
                        }
                    }
                },
            )
        }.onFailure { complete(Result.failure(it)) }
        continuation.invokeOnCancellation { completed.set(true) }
    }

    private fun buildCategories(
        categories: List<BuddyListCategory>,
        profiles: Map<String, UserSimpleInfo>,
        nickMap: Map<String, String>,
        remarkMap: Map<String, String>,
        uins: Map<String, Long>,
        recentByUid: Map<String?, com.tencent.qqnt.kernel.nativeinterface.RecentContactInfo> = emptyMap(),
    ): List<UiCategory> {
        return categories.mapNotNull { category ->
            val categoryId = category.categoryId
            val categoryName = category.categroyName?.takeIf { it.isNotBlank() } ?: "我的好友"
            val buddies = category.buddyUids.orEmpty()
                .asSequence()
                .filter { it.isNotBlank() }
                .distinct()
                .map { uid ->
                    val profile = profiles[uid]
                    val core = profile?.coreInfo
                    val uin = profile?.uin?.takeIf { it > 0L } ?: uins[uid].orZero()
                    val recent = recentByUid[uid]
                    toUiBuddy(
                        uid = uid,
                        uin = uin,
                        nick = core?.nick ?: nickMap[uid],
                        remark = core?.remark ?: remarkMap[uid],
                        avatarPath = recent?.avatarPath.orEmpty(),
                        avatarUrls = listOfNotNull(recent?.avatarUrl?.takeIf { it.isNotBlank() }),
                        categoryId = categoryId,
                        categoryName = categoryName,
                    )
                }
                .toList()
            buddies.takeIf { it.isNotEmpty() }?.let {
                UiCategory(categoryId, categoryName, it)
            }
        }.sortedWith(compareBy<UiCategory> { it.id }.thenBy { it.name })
    }

    private fun toUiBuddy(
        uid: String,
        uin: Long,
        nick: String?,
        remark: String?,
        avatarPath: String,
        avatarUrls: List<String> = emptyList(),
        categoryId: Int,
        categoryName: String,
    ): UiBuddy {
        val displayNick = nick.orEmpty().ifBlank { uid }
        val fallbackUrls = if (uin > 0L) {
            listOf(
                "https://q1.qlogo.cn/g?b=qq&nk=$uin&s=100",
                "https://q2.qlogo.cn/headimg_dl?dst_uin=$uin&spec=100",
                "https://qlogo2.store.qq.com/qzone/$uin/$uin/100",
            )
        } else {
            emptyList()
        }
        return UiBuddy(
            uid = uid,
            uin = uin,
            nick = displayNick,
            remark = remark.orEmpty(),
            avatarPath = avatarPath,
            avatarUrls = avatarUrls + fallbackUrls,
            categoryId = categoryId,
            categoryName = categoryName,
        )
    }

    private fun Long?.orZero(): Long = this ?: 0L

    private fun scheduleRetry(runtime: AppRuntime?, reason: String) {
        synchronized(loadLock) {
            if (retryJob?.isActive == true) return
            retryJob = viewModelScope.launch(Dispatchers.IO) {
                delay(RETRY_DELAY_MS)
                synchronized(loadLock) { retryJob = null }
                if (!loaded && !_loading.value) {
                    Log.d(TAG, "retrying contacts reason=$reason")
                    loadBuddies(runtime, forceRefresh = true)
                }
            }
        }
    }

    override fun onCleared() {
        synchronized(loadLock) {
            loadJob?.cancel()
            retryJob?.cancel()
            refreshIndicatorJob?.cancel()
            loadJob = null
            retryJob = null
            refreshIndicatorJob = null
        }
        super.onCleared()
    }
}
