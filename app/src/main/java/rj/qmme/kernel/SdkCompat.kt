package rj.qmme.kernel

import com.tencent.qqnt.kernel.api.IGroupService
import com.tencent.qqnt.kernel.nativeinterface.IKernelGroupListener

/**
 * Prefers readable Kotlin method names on the single qq-sdk.jar; short JVM
 * names remain as a compatibility fallback.
 */
object SdkCompat {

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
