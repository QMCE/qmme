package rj.qmme.fix

import android.content.Context
import android.util.Log

/**
 * P2-A: Bugly crash reporting bridge.
 *
 * Official ColdStartupTaskFactory includes CrashInitTask which initializes
 * Bugly (com.tencent.eup.CrashReport) early in the startup sequence.
 *
 * This bridge provides equivalent initialization for qmme, ensuring crashes
 * are properly reported to Tencent's backend with correct device/account info.
 */
object BridgeBugly {
    private const val TAG = "QMME-Bugly"
    private var initialized = false
    
    // Config from official Watch 9.0.7
    private const val BUGLY_APP_ID = "1c349d7a6c"  // QQ 手表版专用 appId
    private const val PRODUCT_VERSION = "9.0.7"
    private const val CHANNEL = "watch_qq_9.0.7"
    
    /**
     * Initialize Bugly crash reporting.
     * Should be called early in Application.onCreate(), before any native code loads.
     * 
     * @param context Application context
     * @param userId Optional user ID for crash attribution (e.g., uin)
     */
    fun init(context: Context, userId: String? = null) {
        if (initialized) {
            Log.w(TAG, "Bugly already initialized")
            return
        }
        
        try {
            // Load libBuglyNative.so first (required by CrashReport)
            System.loadLibrary("Bugly_Native")
            Log.d(TAG, "libBugly_Native.so loaded")
            
            // Get MobileQQ singleton for context access
            val mobileQQClass = Class.forName("mqq.app.MobileQQ")
            val sMobileQQField = mobileQQClass.getDeclaredField("sMobileQQ")
            sMobileQQField.isAccessible = true
            val mobileQQ = sMobileQQField.get(null)
            
            if (mobileQQ != null) {
                // Initialize CrashReport using static methods (official pattern)
                // com.tencent.feedback.eup.CrashReport.setUserId(context, uin)
                val crashReportClass = Class.forName("com.tencent.feedback.eup.CrashReport")
                
                // setUserId for account attribution
                if (!userId.isNullOrBlank()) {
                    val setUserIdMethod = crashReportClass.getMethod(
                        "setUserId", 
                        Context::class.java, 
                        String::class.java
                    )
                    setUserIdMethod.invoke(null, context, userId)
                    Log.i(TAG, "Bugly userId set: $userId")
                }
                
                // Enable all thread stack collection
                val setAllThreadStackEnableMethod = crashReportClass.getMethod(
                    "setAllThreadStackEnable",
                    Context::class.java,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType
                )
                setAllThreadStackEnableMethod.invoke(null, context, true, true)
                
                // Set product version and channel
                val setProductVersionMethod = crashReportClass.getMethod(
                    "setProductVersion",
                    Context::class.java,
                    String::class.java
                )
                setProductVersionMethod.invoke(null, context, PRODUCT_VERSION)
                
                val setAppChannelMethod = crashReportClass.getMethod(
                    "setAppChannel",
                    Context::class.java,
                    String::class.java
                )
                setAppChannelMethod.invoke(null, context, CHANNEL)
                
                Log.i(TAG, "Bugly initialized successfully with version=$PRODUCT_VERSION channel=$CHANNEL")
            } else {
                Log.w(TAG, "MobileQQ singleton not available, skipping Bugly init")
            }
            
            initialized = true
        } catch (e: Exception) {
            Log.e(TAG, "Bugly initialization failed", e)
            // Don't fail hard - let the app continue without crash reporting
        }
    }
    
    /**
     * Check if Bugly is initialized.
     */
    fun isInitialized(): Boolean = initialized
    
    /**
     * Report a custom exception/event manually.
     */
    fun reportException(exception: Throwable) {
        if (!initialized) {
            Log.w(TAG, "Bugly not initialized, cannot report exception")
            return
        }
        
        try {
            val crashReportClass = Class.forName("com.tencent.feedback.eup.CrashReport")
            val reportCatchExceptionMethod = crashReportClass.getMethod(
                "reportCatchException",
                Context::class.java,
                Throwable::class.java
            )
            reportCatchExceptionMethod.invoke(null, null, exception)
            Log.i(TAG, "Custom exception reported to Bugly")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to report exception to Bugly", e)
        }
    }
}
