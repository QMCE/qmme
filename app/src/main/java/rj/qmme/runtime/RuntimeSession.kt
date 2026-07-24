package rj.qmme.runtime

import android.os.SystemClock
import mqq.app.AppRuntime

/**
 * Ownership record for one concrete AppRuntime object.
 *
 * A new object always gets a new generation, even when it represents the same
 * account.  Consumers can therefore reject service/UI work that belongs to a
 * previous runtime instance after logout or a runtime replacement.
 */
data class RuntimeSession(
    val generation: Long,
    val runtime: AppRuntime,
    val processName: String?,
    val createdAtElapsedRealtime: Long = SystemClock.elapsedRealtime(),
    val accountUin: String? = null,
) {
    val runtimeIdentity: Int
        get() = System.identityHashCode(runtime)
}
