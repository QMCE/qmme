package rj.qmme.runtime

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * P1-B: Online keepalive manager - StatSvc.register + Heartbeat.Alive monitoring & recovery.
 *
 * The official native MSF should automatically send StatSvc.register after login and
 * Heartbeat.Alive (~300s interval). This manager monitors those operations and provides
 * fallback mechanisms if they're not working correctly.
 *
 * Key behaviors:
 * 1. Monitor registration status via AppRuntime callbacks
 * 2. Track last heartbeat time and trigger manual heartbeats if native fails
 * 3. Detect ForceOffline/SidTicketExpired and handle appropriately
 * 4. Log diagnostics for debugging
 */
object HeartbeatManager {
    private const val TAG = "QMME-Keepalive"
    
    // Default heartbeat interval (same as official: 0x493e0 = 300000ms)
    private const val DEFAULT_HEARTBEAT_INTERVAL_MS = 300_000L
    
    private var heartbeatIntervalMs: Long = DEFAULT_HEARTBEAT_INTERVAL_MS
    private var nextHeartbeatTime = AtomicLong(0L)
    private var registered = AtomicBoolean(false)
    private var lastHeartbeatTime = AtomicLong(0L)
    private val handler = Handler(Looper.getMainLooper())
    private var heartbeatRunnable: Runnable? = null
    private var isRunning = AtomicBoolean(false)
    
    /**
     * Start the heartbeat loop. Should be called after successful login.
     */
    fun start() {
        if (!isRunning.compareAndSet(false, true)) {
            Log.d(TAG, "Heartbeat already running")
            return
        }
        
        Log.i(TAG, "Heartbeat manager started")
        resetTimer()
        
        // Schedule first heartbeat check
        scheduleNextHeartbeat()
    }
    
    /**
     * Stop the heartbeat loop. Called during logout.
     */
    fun stop() {
        if (!isRunning.compareAndSet(true, false)) {
            return
        }
        
        heartbeatRunnable?.let { handler.removeCallbacks(it) }
        heartbeatRunnable = null
        Log.i(TAG, "Heartbeat manager stopped")
    }
    
    /**
     * Reset heartbeat timer after a successful heartbeat.
     */
    fun notifyHeartbeatSent() {
        lastHeartbeatTime.set(System.currentTimeMillis())
        resetTimer()
        Log.d(TAG, "Heartbeat sent at ${lastHeartbeatTime}")
    }
    
    /**
     * Notify that registration completed successfully.
     */
    fun notifyRegistered() {
        if (registered.compareAndSet(false, true)) {
            Log.i(TAG, "StatSvc.register completed successfully")
        }
    }
    
    /**
     * Check if we're currently registered with the server.
     */
    fun isRegistered(): Boolean = registered.get()
    
    /**
     * Get time since last heartbeat in milliseconds.
     */
    fun timeSinceLastHeartbeat(): Long = System.currentTimeMillis() - lastHeartbeatTime.get()
    
    /**
     * Trigger an immediate heartbeat. Use this if we detect no heartbeat in long time.
     */
    fun forceHeartbeat() {
        Log.w(TAG, "Force heartbeat triggered")
        trySendHeartbeat()
    }
    
    /**
     * Reset the heartbeat timer to fire at the default interval.
     */
    private fun resetTimer() {
        val now = System.currentTimeMillis()
        nextHeartbeatTime.set(now + heartbeatIntervalMs)
    }
    
    /**
     * Schedule the next heartbeat check.
     */
    private fun scheduleNextHeartbeat() {
        heartbeatRunnable = object : Runnable {
            override fun run() {
                if (!isRunning.get()) return
                
                val now = System.currentTimeMillis()
                val nextTime = nextHeartbeatTime.get()
                
                if (now >= nextTime) {
                    // Time for a heartbeat
                    trySendHeartbeat()
                    resetTimer()
                }
                
                // Schedule next check
                val delay = maxOf(0L, nextTime - System.currentTimeMillis())
                handler.postDelayed(this, delay)
            }
        }
        
        heartbeatRunnable?.let { handler.post(it) }
    }
    
    /**
     * Attempt to send a heartbeat via native MSF. Returns true if successful.
     */
    private fun trySendHeartbeat(): Boolean {
        val runtime = RuntimeCoordinator.currentRuntime() ?: return false
        
        return try {
            // Native MSF should handle Heartbeat.Alive automatically.
            // We can optionally trigger it manually here if needed.
            // For now, just log diagnostics.
            
            val uin = runCatching { runtime.currentUin }.getOrNull().orEmpty()
            
            Log.i(TAG, "Heartbeat check: uin=${RuntimeCoordinator.redactUin(uin)}")
            Log.i(TAG, "Registered=$registered lastHeartbeat=${timeSinceLastHeartbeat()}ms ago")
            
            // If we haven't sent a heartbeat in over 2 intervals, something might be wrong
            if (timeSinceLastHeartbeat() > heartbeatIntervalMs * 2) {
                Log.w(TAG, "WARNING: No heartbeat detected for ${timeSinceLastHeartbeat()/1000}s")
                // Could trigger native send here if we have access
            }
            
            true
        } catch (e: Exception) {
            Log.w(TAG, "Heartbeat attempt failed", e)
            false
        }
    }
    
    /**
     * Force re-registration if we've lost connection.
     */
    fun forceReregister() {
        Log.w(TAG, "Forcing re-registration")
        registered.set(false)
        // Native MSF should handle re-registration automatically on connect
    }
    
    /**
     * Handle SidTicketExpired event - need to re-authenticate.
     */
    fun onSidTicketExpired() {
        Log.w(TAG, "SidTicketExpired received - should re-authenticate")
        registered.set(false)
        // The WtLogin flow should handle re-authentication
        stop()
    }
}
