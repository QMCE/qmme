package rj.qmme

/**
 * Runtime feature flags, accessible from ASM-patched qq-sdk.jar code.
 */
@Suppress("unused")
object Flag {
    /** Prevent QQ SDK QLog from writing local files while keeping Android logcat output.
     * THIS IS USED IN QLOG CODE.
     * DO NOT DELETE.
     */
    @JvmField
    var DISABLE_QLOG_LOCAL_WRITE = true
}
