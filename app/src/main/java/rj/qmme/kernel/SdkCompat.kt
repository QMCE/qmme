package rj.qmme.kernel

import com.tencent.qqnt.kernel.api.IBuddyService
import com.tencent.qqnt.kernel.api.IGroupService
import com.tencent.qqnt.kernel.api.IMsgService
import com.tencent.qqnt.kernel.api.IRecentContactService
import com.tencent.qqnt.kernel.invorker.IExpandNotificationListener
import com.tencent.qqnt.kernel.nativeinterface.IKernelBuddyListener
import com.tencent.qqnt.kernel.nativeinterface.IKernelGroupListener
import com.tencent.qqnt.kernel.nativeinterface.IKernelMsgListener
import com.tencent.qqnt.kernel.nativeinterface.IKernelRecentContactListener
import com.tencent.qqnt.kernel.nativeinterface.RecentContactInfo

/**
 * Prefers readable Kotlin method names on the single qq-sdk.jar; short JVM
 * names remain as a compatibility fallback.
 */
object SdkCompat {

    fun addMsgListener(msgService: IMsgService, listener: IKernelMsgListener) {
        invokeVoid(
            msgService,
            IMsgService::class.java,
            listOf("addMsgListener", "o"),
            arrayOf(IKernelMsgListener::class.java),
            listener,
        )
    }

    fun removeMsgListener(msgService: IMsgService, listener: IKernelMsgListener) {
        invokeVoid(
            msgService,
            IMsgService::class.java,
            listOf("removeMsgListener", "d"),
            arrayOf(IKernelMsgListener::class.java),
            listener,
        )
    }

    /** Recent-contact cache read; returns null when the kernel call fails. */
    fun getRecentContactFromCache(
        recentService: IRecentContactService,
        listType: Int,
    ): List<RecentContactInfo>? = runCatching {
        invokeReturning(
            recentService,
            IRecentContactService::class.java,
            listOf("getRecentContactFromCache", "D"),
            arrayOf(Int::class.javaPrimitiveType!!),
            listType,
        ) as? List<RecentContactInfo>
    }.getOrNull()

    fun addBuddyListener(buddyService: IBuddyService, listener: IKernelBuddyListener?) {
        invokeVoid(
            buddyService,
            IBuddyService::class.java,
            listOf("addBuddyListener", "v"),
            arrayOf(IKernelBuddyListener::class.java),
            listener,
        )
    }

    fun removeBuddyListener(buddyService: IBuddyService, listener: IKernelBuddyListener?) {
        invokeVoid(
            buddyService,
            IBuddyService::class.java,
            listOf("removeBuddyListener", "c"),
            arrayOf(IKernelBuddyListener::class.java),
            listener,
        )
    }

    fun setExpandNotificationListener(
        recentService: IRecentContactService,
        listener: IExpandNotificationListener?,
    ): Boolean = runCatching {
        invokeVoid(
            recentService,
            IRecentContactService::class.java,
            listOf("setExpandNotificationListener", "l"),
            arrayOf(IExpandNotificationListener::class.java),
            listener,
        )
        true
    }.getOrDefault(false)

    fun clearExpandNotificationListener(recentService: IRecentContactService): Boolean =
        setExpandNotificationListener(recentService, null)

    fun addKernelRecentContactListener(
        recentService: IRecentContactService,
        listener: IKernelRecentContactListener,
    ): Boolean = runCatching {
        invokeVoid(
            recentService,
            IRecentContactService::class.java,
            listOf("addKernelRecentContactListener", "g"),
            arrayOf(IKernelRecentContactListener::class.java),
            listener,
        )
        true
    }.getOrDefault(false)

    fun removeKernelRecentContactListener(
        recentService: IRecentContactService,
        listener: IKernelRecentContactListener,
    ): Boolean = runCatching {
        invokeVoid(
            recentService,
            IRecentContactService::class.java,
            listOf("removeKernelRecentContactListener", "x"),
            arrayOf(IKernelRecentContactListener::class.java),
            listener,
        )
        true
    }.getOrDefault(false)

    fun addGroupListener(groupService: IGroupService, listener: IKernelGroupListener?) {
        invokeVoid(
            groupService,
            IGroupService::class.java,
            listOf("addGroupListener", "i"),
            arrayOf(IKernelGroupListener::class.java),
            listener,
        )
    }

    fun removeGroupListener(groupService: IGroupService, listener: IKernelGroupListener?) {
        invokeVoid(
            groupService,
            IGroupService::class.java,
            listOf("removeGroupListener", "p"),
            arrayOf(IKernelGroupListener::class.java),
            listener,
        )
    }

    private fun invokeVoid(
        target: Any,
        iface: Class<*>,
        names: List<String>,
        paramTypes: Array<Class<*>>,
        vararg args: Any?,
    ) {
        invokeReturning(target, iface, names, paramTypes, *args)
    }

    private fun invokeReturning(
        target: Any,
        iface: Class<*>,
        names: List<String>,
        paramTypes: Array<Class<*>>,
        vararg args: Any?,
    ): Any? {
        for (name in names) {
            runCatching {
                return iface.getMethod(name, *paramTypes).invoke(target, *args)
            }
            runCatching {
                return target.javaClass.getMethod(name, *paramTypes).invoke(target, *args)
            }
        }
        error("${names.first()} unavailable on ${target.javaClass.name}")
    }
}
