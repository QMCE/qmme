package rj.qmme.data

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.tencent.qqnt.kernel.api.IProfileService
import com.tencent.qqnt.kernel.api.impl.ProfileService
import com.tencent.qqnt.kernel.nativeinterface.CoreInfo
import com.tencent.qqnt.kernel.nativeinterface.IKernelProfileListener
import com.tencent.qqnt.kernel.nativeinterface.StatusInfo
import com.tencent.qqnt.kernel.nativeinterface.UserDetailInfo
import com.tencent.qqnt.kernel.nativeinterface.UserSimpleInfo
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "QMME"

/**
 * 在线状态缓存。只关心自己的状态（selfUid）。
 *
 * 1. start() 注册 IKernelProfileListener + startStatusPolling(true)
 * 2. onStatusUpdate / onSelfStatusChanged 收到推送后 merge 进 cache
 * 3. UI 通过 addObserver 注册回调，在 UI 线程收到通知后刷新
 */
object OnlineStatus {
    private val cache = ConcurrentHashMap<String, StatusInfo>()
    private val observers = CopyOnWriteArrayList<() -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var started = false
    @Volatile
    private var selfUid: String = ""

    fun addObserver(cb: () -> Unit) {
        if (cb !in observers) observers.add(cb)
    }

    fun removeObserver(cb: () -> Unit) {
        observers.remove(cb)
    }

    /** 已有缓存记录（区分"未初始化"和"离线"） */
    fun known(): Boolean = selfUid.isNotEmpty() && cache.containsKey(selfUid)

    fun isOnline(): Boolean {
        val s = cache[selfUid] ?: return false
        return s.status != 0 && s.status != 20
    }

    /** "手机在线" / "在线" / "离线" / null（未知） */
    fun describe(): String? {
        val s = cache[selfUid] ?: return null
        val d = s.termDesc
        if (!d.isNullOrEmpty()) return d
        return if (s.status != 0 && s.status != 20) "在线" else "离线"
    }

    fun start(profileService: IProfileService, uid: String) {
        if (started) return
        selfUid = uid
        try {
            // The matching Watch APK exposes this original profile listener
            // method under its shipped obfuscated ABI name, M().  Kotlin's
            // metadata still calls it addProfileListener(), so a direct Kotlin
            // call compiles to the absent phone-build symbol and crashes on the
            // Watch runtime. Resolve the actual APK ABI without introducing a
            // hand-written QQNT compatibility class.
            addWatchProfileListener(profileService, Listener)
            val native = (profileService as? ProfileService)?.service
            native?.startStatusPolling(true)
            // 预热缓存，并立刻发布给顶栏等已注册 UI。
            val map = native?.getStatusInfo("qmce", arrayListOf(uid))
            if (!map.isNullOrEmpty()) {
                merge(map)
                notifyObservers()
            }
            started = true
            Log.d(TAG, "OnlineStatus: started, uid=$uid")
        } catch (error: Throwable) {
            // QQ runtime APIs are version-specific and can fail with a
            // LinkageError, not only an Exception. Status must never take down
            // the host UI if a future APK changes this private ABI again.
            Log.w(TAG, "OnlineStatus: start failed", error)
            notifyObservers()
        }
    }

    /**
     * The Watch APK's [IProfileService] exposes listener registration as `M`,
     * while its Kotlin metadata advertises the deobfuscated
     * `addProfileListener` name.  Calling either name statically is therefore
     * unsafe across the Kotlin/DEX boundary.  Looking up the concrete method
     * keeps us compatible with the class actually shipped in qq-core.
     */
    private fun addWatchProfileListener(
        profileService: IProfileService,
        listener: IKernelProfileListener,
    ) {
        val listenerType = IKernelProfileListener::class.java
        val method = profileService.javaClass.methods.firstOrNull { candidate ->
            candidate.name == "M" &&
                candidate.parameterTypes.size == 1 &&
                candidate.parameterTypes[0] == listenerType
        } ?: profileService.javaClass.declaredMethods.firstOrNull { candidate ->
            candidate.name == "M" &&
                candidate.parameterTypes.size == 1 &&
                candidate.parameterTypes[0] == listenerType
        } ?: throw NoSuchMethodException(
            "${profileService.javaClass.name}.M(${listenerType.name})",
        )
        if (!method.isAccessible) method.isAccessible = true
        method.invoke(profileService, listener)
    }

    private fun merge(map: HashMap<String, StatusInfo>) {
        for ((uid, info) in map) {
            if (!uid.isNullOrEmpty() && info != null) cache[uid] = info
        }
    }

    private fun notifyObservers() {
        mainHandler.post { observers.forEach { runCatching { it() } } }
    }

    private object Listener : IKernelProfileListener {
        override fun onStatusUpdate(map: HashMap<String, StatusInfo>?) {
            if (!map.isNullOrEmpty()) {
                merge(map); notifyObservers()
            }
        }

        override fun onStatusAsyncFieldUpdate(map: HashMap<String, StatusInfo>?) {
            if (!map.isNullOrEmpty()) {
                merge(map); notifyObservers()
            }
        }

        override fun onSelfStatusChanged(statusInfo: StatusInfo?) {
            if (statusInfo != null && selfUid.isNotEmpty()) {
                cache[selfUid] = statusInfo
                notifyObservers()
            }
        }

        override fun onProfileSimpleChanged(map: HashMap<String, UserSimpleInfo>?) {}
        override fun onStrangerRemarkChanged(map: HashMap<String, CoreInfo>?) {}
        override fun onUserDetailInfoChanged(userDetailInfo: UserDetailInfo?) {}
    }
}
