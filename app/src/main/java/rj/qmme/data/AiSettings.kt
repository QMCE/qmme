package rj.qmme.data

import android.content.Context
import android.content.Context.MODE_PRIVATE

/**
 * Phone-side AI endpoint configuration used by the message summarizer.
 *
 * Ships a built-in free endpoint (opencode zen, model `big-pickle`) that works
 * anonymously, so the summarizer works out of the box. A fully entered custom
 * endpoint overrides it; a partially filled custom config disables the
 * fallback so the user notices their settings are incomplete.
 */
object AiSettings {
    private const val PREFS_NAME = "qmme_ai_settings"
    private const val KEY_BASE_URL = "ai_base_url"
    private const val KEY_API_KEY = "ai_api_key"
    private const val KEY_MODEL = "ai_model"

    // opencode zen free model: anonymous access, zero cost.
    private const val BUILTIN_BASE_URL = "https://opencode.ai/zen/v1/chat/completions"
    private const val BUILTIN_MODEL = "big-pickle"

    data class Endpoint(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
    )

    fun baseUrl(context: Context): String =
        prefs(context).getString(KEY_BASE_URL, "").orEmpty()

    fun setBaseUrl(context: Context, value: String) {
        prefs(context).edit().putString(KEY_BASE_URL, value.trim()).apply()
    }

    fun apiKey(context: Context): String =
        prefs(context).getString(KEY_API_KEY, "").orEmpty()

    fun setApiKey(context: Context, value: String) {
        prefs(context).edit().putString(KEY_API_KEY, value.trim()).apply()
    }

    fun model(context: Context): String =
        prefs(context).getString(KEY_MODEL, "").orEmpty()

    fun setModel(context: Context, value: String) {
        prefs(context).edit().putString(KEY_MODEL, value.trim()).apply()
    }

    /** True when nothing has been configured and the built-in endpoint applies. */
    fun isBuiltin(context: Context): Boolean =
        baseUrl(context).isBlank() && apiKey(context).isBlank() && model(context).isBlank()

    /**
     * Returns a normalized endpoint: the built-in free one when nothing is
     * configured, the custom one when all three fields are present, or null
     * when a partial custom config would silently mix with the builtin.
     */
    fun resolve(context: Context): Endpoint? {
        val url = baseUrl(context)
        val key = apiKey(context)
        val model = model(context)
        if (url.isBlank() && key.isBlank() && model.isBlank()) {
            return Endpoint(baseUrl = BUILTIN_BASE_URL, apiKey = "", model = BUILTIN_MODEL)
        }
        if (url.isBlank() || key.isBlank() || model.isBlank()) return null
        return Endpoint(
            baseUrl = normalizeCompletionsUrl(url),
            apiKey = key,
            model = model,
        )
    }

    /** Accepts host, host/v1 or a full .../chat/completions URL. */
    fun normalizeCompletionsUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        return when {
            trimmed.endsWith("/chat/completions") -> trimmed
            trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
            else -> trimmed
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
}
