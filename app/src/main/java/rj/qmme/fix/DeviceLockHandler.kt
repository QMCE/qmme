package rj.qmme.fix

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import rj.qmme.diagnostics.OfflineDiagnostics
import java.util.concurrent.CopyOnWriteArrayList

/**
 * P2-D: Device Lock verification handler.
 * 
 * When WtLogin detects unusual login patterns, it returns DevlockInfo requiring
 * SMS verification or URL-based verification before allowing login.
 * 
 * This class provides:
 * 1. Detection of Devlock challenges from WtLogin
 * 2. User notification and guidance
 * 3. Optional automatic URL validation (risky)
 * 4. Manual code input support
 */
object DeviceLockHandler {
    private const val TAG = "QMME-Devlock"
    
    // Registered callbacks for devlock events
    private val callbacks = CopyOnWriteArrayList<DevlockCallback>()
    
    // Current pending challenge (if any)
    private var currentChallenge: DevlockChallenge? = null
    
    /**
     * Register callback for device lock events.
     */
    fun register(callback: DevlockCallback) {
        callbacks.add(callback)
        Log.d(TAG, "Devlock callback registered")
    }
    
    /**
     * Unregister callback.
     */
    fun unregister(callback: DevlockCallback) {
        callbacks.remove(callback)
    }
    
    /**
     * Notify all registered callbacks of a devlock event.
     * Called by WtLogin when DevlockInfo is received.
     */
    fun notifyDevlock(challengeData: Map<String, Any>) {
        val challenge = DevlockChallenge.fromMap(challengeData)
        
        Log.w(TAG, "Device lock detected: type=${challenge.type} hasUrl=${!challenge.url.isNullOrBlank()} smsCount=${challenge.availableSmsCount}")
        
        currentChallenge = challenge
        
        // Notify all callbacks
        callbacks.forEach { it.onDevlock(challenge) }
        
        // Also log diagnostic info
        OfflineDiagnostics.record(null, "devlock_detected", "type=${challenge.type} urlPresent=${!challenge.url.isNullOrBlank()}")
    }
    
    /**
     * Report successful verification (after user entered code).
     */
    fun onVerificationSuccess(code: String) {
        Log.i(TAG, "Verification code submitted: ${code.takeLast(4)}")
        currentChallenge = null
        
        callbacks.forEach { it.onVerificationSuccess(code) }
    }
    
    /**
     * Cancel current challenge.
     */
    fun cancelChallenge() {
        Log.w(TAG, "Device lock challenge cancelled by user")
        currentChallenge = null
        callbacks.forEach { it.onCancelled() }
    }
    
    /**
     * Get current pending challenge, if any.
     */
    fun getCurrentChallenge(): DevlockChallenge? = currentChallenge
    
    /**
     * Check if there's an active devlock challenge.
     */
    fun hasActiveChallenge(): Boolean = currentChallenge != null
    
    /**
     * Open verification page with provided URL.
     * Only call this after user consent!
     */
    fun openVerificationPage(context: Context, activity: Activity, url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                this.data = android.net.Uri.parse(url)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            activity.startActivity(intent)
            Log.i(TAG, "Opened verification page: $url")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open verification page", e)
        }
    }
    
    /**
     * Show dialog提示用户输入验证码。
     * Requires UI thread and appropriate permissions.
     */
    fun showVerificationDialog(activity: Activity, challenge: DevlockChallenge) {
        Log.i(TAG, "Showing verification dialog: type=${challenge.type}")
        
        // TODO: Implement proper dialog with EditText for code input
        // For now, just log the guidance
        val guidance = when (challenge.type) {
            "sms" -> "设备锁：请输入短信验证码完成验证\n短信号码：${challenge.mobile}"
            "url" -> "设备锁：请打开以下链接完成验证\n${challenge.url ?: "N/A"}"
            else -> "设备锁验证：请联系 QQ 客服或前往官方客户端操作"
        }
        
        Log.w(TAG, guidance)
    }
    
    /**
     * Device lock information container.
     */
    data class DevlockChallenge(
        val type: String,              // "sms", "url", or other
        val mobile: String?,           // Phone number (partial masked)
        val availableSmsCount: Int,    // Remaining SMS attempts
        val url: String?,              // Verification URL (optional)
        val unionVerifyUrl: String?,   // Alternative verification URL
        val allowSet: Boolean,         // Can set custom password?
        val extraData: Map<String, Any?>?  // Additional server data
    ) {
        companion object {
            /**
             * Create DevlockChallenge from raw map data.
             */
            fun fromMap(data: Map<String, Any>): DevlockChallenge {
                return DevlockChallenge(
                    type = data["type"] as? String ?: "unknown",
                    mobile = data["mobile"] as? String,
                    availableSmsCount = (data["availableMsgCount"] as? Int) ?: 0,
                    url = data["url"] as? String,
                    unionVerifyUrl = data["unionVerifyUrl"] as? String,
                    allowSet = data["allowSet"] as? Boolean ?: false,
                    extraData = null
                )
            }
        }
        
        override fun toString(): String {
            return "Devlock(type=$type, mobile=$mobile, smsLeft=$availableSmsCount, url=${url?.take(20)}...)"
        }
    }
    
    /**
     * Callback interface for device lock events.
     */
    interface DevlockCallback {
        /**
         * Called when a device lock challenge is detected.
         * The app should prompt the user for action.
         */
        fun onDevlock(challenge: DevlockChallenge)
        
        /**
         * Called when user successfully provides verification code.
         * @param code The verification code (usually 6-digit SMS code)
         */
        fun onVerificationSuccess(code: String)
        
        /**
         * Called when challenge is cancelled by user.
         */
        fun onCancelled()
    }
}
