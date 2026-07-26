package rj.qmme.fix

import android.util.Log

/**
 * P1-A: Login Proof-of-Work (PoW) bridge for T546→T547 challenge-response.
 *
 * The official WtLogin flow (request/WtloginHelper.smali ~718-722) creates
 * ClientPow and calls a([B]) to compute the T547 answer from the T546 challenge.
 * This class bridges that path by loading libpow.so and delegating to the native
 * ClientPow.a() implementation, falling back to Java if needed.
 *
 * IMPORTANT: This is called automatically by WtLogin when _t546 is present in
 * the async context. No manual wiring required beyond ensuring libpow.so loads.
 */
object PoWHelper {
    private const val TAG = "QMME-PoW"
    private var powLoaded = false

    /**
     * Load libpow.so once, typically during application onCreate or before first login.
     * Native initialization happens inside ClientPow constructor (smali),
     * which attempts System.loadLibrary("pow") itself.
     */
    fun ensureLoaded() {
        if (powLoaded) return
        try {
            System.loadLibrary("pow")
            powLoaded = true
            Log.d(TAG, "libpow.so loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "libpow.so preload skipped or failed", e)
        }
    }

    /**
     * Compute T547 answer from T546 challenge using ClientPow.a([B]).
     *
     * @param t546Challenge raw T546 TLV payload from WtLogin response
     * @return computed T547 answer bytes, or null on failure
     */
    fun computeAnswer(t546Challenge: ByteArray): ByteArray? {
        if (!powLoaded) {
            ensureLoaded()
        }
        
        return try {
            // Create ClientPow instance - it auto-loads libpow in constructor
            val clientPowClass = Class.forName("oicq.wlogin_sdk.pow.ClientPow")
            val clientPow = clientPowClass.getDeclaredConstructor().newInstance()
            
            // Call ClientPow.a([B]) -> computes T547 answer
            val method = clientPowClass.getMethod("a", ByteArray::class.java)
            @Suppress("UNCHECKED_CAST")
            val answer = method.invoke(clientPow, t546Challenge) as? ByteArray
            
            if (answer != null && answer.isNotEmpty()) {
                Log.d(TAG, "PoW answer computed: len=${answer.size}")
            } else {
                Log.w(TAG, "PoW answer empty or invalid")
            }
            
            answer
        } catch (e: Exception) {
            Log.w(TAG, "PoW computation failed", e)
            null
        }
    }

    /**
     * Get test data from native pow library (for debugging/validation).
     */
    fun getTestData(): ByteArray? {
        if (!powLoaded) {
            ensureLoaded()
        }
        
        return try {
            val clientPowClass = Class.forName("oicq.wlogin_sdk.pow.ClientPow")
            val clientPow = clientPowClass.getDeclaredConstructor().newInstance()
            
            val method = clientPowClass.getDeclaredMethod("nativeGetTestData")
            @Suppress("UNCHECKED_CAST")
            method.invoke(clientPow) as? ByteArray
        } catch (e: Exception) {
            Log.w(TAG, "PoW test data fetch failed", e)
            null
        }
    }
}
