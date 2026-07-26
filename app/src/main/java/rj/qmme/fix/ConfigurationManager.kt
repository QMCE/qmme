package rj.qmme.fix

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * P2-B: Configuration Manager for RDelivery/unitedconfig functionality.
 * 
 * This class bridges the official RDelivery SDK to fetch remote configuration
 * including FEKit command whitelist, security flags, and other runtime settings.
 * 
 * Official flow: ColdStartup → RDelivery.init() → HTTP request → JSON response
 *               → SP persistence → ChannelManager.getCmdWhiteList() updates
 */
object ConfigurationManager {
    private const val TAG = "QMME-Config"
    
    // Configuration keys
    private const val SP_NAME = "sp_security_name"
    private const val SP_FLAG_NAME = "sp_security_flag_name"
    private const val CMD_WHITELIST_KEY = "cmd_whitelist"
    
    // Remote config server (from official 9.0.7)
    private const val CONFIG_SERVER_URL = "https://config.qq.com/qqnt/watch/9.0.7/config.json"
    private const val WHITELIST_UPDATE_URL = "https://config.qq.com/qqnt/whitelist/update"
    
    private var initialized = false
    private val executor = Executors.newSingleThreadExecutor()
    private var sharedPreferences: SharedPreferences? = null
    
    /**
     * Initialize configuration manager. Should be called early in Application onCreate().
     * @param context Application context
     */
    fun init(context: Context) {
        if (initialized) return
        
        try {
            sharedPreferences = context.getSharedPreferences(SP_NAME, Context.MODE_PRIVATE)
            
            // Pre-enable security flag if disabled
            val spFlag = sharedPreferences?.getInt(SP_FLAG_NAME, -1)
            if (spFlag == 0) {
                sharedPreferences?.edit()?.putInt(SP_FLAG_NAME, 2)?.apply()
                Log.w(TAG, "Re-enabled security flag from persisted 0")
            }
            
            // Load existing whitelist
            loadCachedWhitelist()
            
            initialized = true
            Log.i(TAG, "Configuration manager initialized")
            
            // Start background fetch
            fetchRemoteConfig()
        } catch (e: Exception) {
            Log.e(TAG, "Configuration manager init failed", e)
        }
    }
    
    /**
     * Fetch latest configuration from server.
     * Called asynchronously after app start.
     */
    private fun fetchRemoteConfig() {
        executor.execute {
            try {
                val url = URL(CONFIG_SERVER_URL)
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 10000
                connection.requestMethod = "GET"
                
                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val inputStream = BufferedInputStream(connection.inputStream)
                    val jsonString = inputStream.use { it.reader().readText() }
                    
                    parseAndApplyConfig(jsonString)
                } else {
                    Log.w(TAG, "Config fetch failed: HTTP $responseCode")
                }
                
                connection.disconnect()
            } catch (e: Exception) {
                Log.w(TAG, "Remote config fetch exception", e)
                // Fallback to cached config
            }
        }
    }
    
    /**
     * Parse remote config JSON and apply changes.
     */
    private fun parseAndApplyConfig(jsonString: String) {
        try {
            val jsonObject = JSONObject(jsonString)
            
            // Extract command whitelist if present
            if (jsonObject.has("whitelist")) {
                val whitelistJson = jsonObject.getJSONArray("whitelist")
                val whitelist = mutableListOf<String>()
                
                for (i in 0 until whitelistJson.length()) {
                    whitelist.add(whitelistJson.getString(i))
                }
                
                saveWhitelist(whitelist)
                Log.i(TAG, "Loaded ${whitelist.size} commands from whitelist")
                
                // Notify FEKit to update whitelist
                notifyFeKitWhitelistUpdate(whitelist)
            }
            
            // Update security flags
            if (jsonObject.has("security_flags")) {
                val flags = jsonObject.getJSONObject("security_flags")
                if (flags.has("sign_enabled")) {
                    val enabled = flags.getBoolean("sign_enabled")
                    sharedPreferences?.edit()
                        ?.putInt(SP_FLAG_NAME, if (enabled) 2 else 0)
                        ?.apply()
                }
            }
            
            Log.i(TAG, "Remote config applied successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse config JSON", e)
        }
    }
    
    /**
     * Save command whitelist to SharedPreferences.
     */
    private fun saveWhitelist(whitelist: List<String>) {
        val serialized = whitelist.joinToString("|")
        sharedPreferences?.edit()?.putString(CMD_WHITELIST_KEY, serialized)?.apply()
        
        // Also persist to native shared preferences (if needed)
        persistToNative()
    }
    
    /**
     * Load cached whitelist from SharedPreferences.
     */
    private fun loadCachedWhitelist() {
        val cached = sharedPreferences?.getString(CMD_WHITELIST_KEY, null) ?: return
        val whitelist = cached.split("|").filter { it.isNotEmpty() }
        
        if (whitelist.isNotEmpty()) {
            Log.i(TAG, "Loaded ${whitelist.size} cached whitelist entries")
        }
    }
    
    /**
     * Notify FEKit to update command whitelist.
     * Calls FEKit.getInstance().updateCmdWhiteList(list)
     */
    private fun notifyFeKitWhitelistUpdate(whitelist: List<String>) {
        try {
            val fekitClass = Class.forName("com.tencent.mobileqq.fe.FEKit")
            val getInstanceMethod = fekitClass.getMethod("getInstance")
            val fekitInstance = getInstanceMethod.invoke(null)
            
            val updateMethod = fekitClass.getMethod(
                "updateCmdWhiteList",
                Array<String>::class.java
            )
            updateMethod.invoke(fekitInstance, whitelist.toTypedArray())
            
            Log.i(TAG, "FEKit whitelist updated: ${whitelist.size} commands")
        } catch (e: Exception) {
            Log.w(TAG, "FEKit whitelist update failed", e)
            // Don't fail hard - signing will still work with default whitelist
        }
    }
    
    /**
     * Persist whitelist to native shared preferences (unitedconfig mechanism).
     * This simulates what libunitedconfig.so does internally.
     */
    private fun persistToNative() {
        try {
            // Try to call unitedconfig native method if available
            val unitedConfigClass = Class.forName("com.tencent.mobileqq.unitedconfig.UnitedConfig")
            val syncMethod = unitedConfigClass.getMethod("syncLocalData")
            syncMethod.invoke(null)
            
            Log.d(TAG, "UnitedConfig local data synced")
        } catch (e: Exception) {
            // Native unitedconfig not loaded or not available - fine
            Log.d(TAG, "UnitedConfig not available, using SP only")
        }
    }
    
    /**
     * Get current command whitelist.
     * Returns empty list if not loaded yet.
     */
    fun getCommandWhitelist(): List<String> {
        val cached = sharedPreferences?.getString(CMD_WHITELIST_KEY, null) ?: return emptyList()
        return cached.split("|").filter { it.isNotEmpty() }
    }
    
    /**
     * Force refresh configuration immediately.
     */
    fun forceRefresh() {
        fetchRemoteConfig()
    }
    
    /**
     * Check if configuration is initialized and ready.
     */
    fun isInitialized(): Boolean = initialized
    
    /**
     * Get current security flag status.
     * @return 2=enabled, 0=disabled, -1=unknown
     */
    fun getSecurityFlagStatus(): Int {
        return sharedPreferences?.getInt(SP_FLAG_NAME, -1) ?: -1
    }
    
    /**
     * Manual whitelist setter (for testing or emergency override).
     */
    fun setWhitelistManually(whitelist: List<String>) {
        saveWhitelist(whitelist)
        notifyFeKitWhitelistUpdate(whitelist)
    }
}
