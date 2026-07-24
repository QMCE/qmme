package rj.qmme.runtime

/**
 * Project-owned lifecycle state for the embedded MobileQQ/AppRuntime pair.
 *
 * This deliberately does not mirror MobileQQ.mRuntimeState.  The latter is an
 * implementation detail of the embedded runtime and can be changed by the
 * upstream library without providing a reliable ownership boundary for this
 * application.
 */
enum class RuntimeLifecycleState {
    COLD,
    ATTACHING,
    APPLICATION_READY,
    RUNTIME_CREATING,
    RUNTIME_CREATED,
    RUNTIME_STARTING,
    RUNTIME_RUNNING,
    ACCOUNT_BINDING,
    ACCOUNT_BOUND,
    KERNEL_STARTING,
    ONLINE,
    LOGGING_OUT,
    RELEASING,
    STOPPED,
    FAILED,
}
