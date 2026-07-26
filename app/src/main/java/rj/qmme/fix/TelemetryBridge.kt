package rj.qmme.fix

import android.util.Log

/**
 * P2-C: Telemetry bridge for native callback support.
 *
 * Official ProjectKernelBootstrap.java has OpentelemetryTracePlan as null,
 * causing NPE when native code tries to call onDataReport(). This bridge
 * provides a non-null implementation that logs telemetry locally.
 */
object TelemetryBridge {
    private const val TAG = "QMME-Telemetry"
    
    /**
     * Get the OpenTelemetry trace plan instance.
     * Returns non-null implementation compatible with official API.
     */
    @Suppress("UNCHECKED_CAST")
    fun getTracePlan(): Any {
        return ProjectOpenTelemetryTracePlan
    }
    
    /**
     * Initialize telemetry system. Should be called during kernel bootstrap.
     */
    fun init() {
        // Set static field if needed (ProjectKernelBootstrap.sTracePlan)
        try {
            val bootstrapClass = Class.forName("rj.qmme.kernel.ProjectKernelBootstrap")
            val tracePlanField = bootstrapClass.getDeclaredField("sTracePlan")
            tracePlanField.isAccessible = true
            
            // Check if already set
            if (tracePlanField.get(null) == null) {
                tracePlanField.set(null, getTracePlan())
                Log.i(TAG, "ProjectKernelBootstrap sTracePlan initialized")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not initialize ProjectKernelBootstrap sTracePlan", e)
            // Not critical - fallback in native code handles this
        }
    }
    
    /**
     * OpenTelemetry implementation that bridges to native callbacks.
     * Uses Any type to avoid dependency on missing interfaces.
     * Logs all telemetry locally for debugging purposes.
     */
    @Suppress("unused")
    private object ProjectOpenTelemetryTracePlan {
        fun report(key: String?, value: Any?) {
            if (key == null || value == null) return
            
            // Log locally for debugging
            when {
                key.startsWith("pow_") -> {
                    Log.d(TAG, "PoW telemetry: $key=$value")
                }
                key.startsWith("heartbeat_") -> {
                    Log.d(TAG, "Heartbeat telemetry: $key=$value")
                }
                key.startsWith("sec_") || key.startsWith("sign_") -> {
                    Log.d(TAG, "Security telemetry: $key=$value")
                }
                else -> {
                    Log.v(TAG, "Telemetry: $key=$value")
                }
            }
        }
        
        fun flush() {
            Log.d(TAG, "Telemetry flushed (local logging only)")
        }
        
        fun shutdown() {
            flush()
            Log.d(TAG, "Telemetry shutdown")
        }
        
        fun enable(enabled: Boolean) {
            Log.d(TAG, "Telemetry ${if (enabled) "enabled" else "disabled"}")
        }
    }
}
