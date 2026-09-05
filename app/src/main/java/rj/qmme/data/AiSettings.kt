package rj.qmme.data

import android.content.Context
import android.content.Context.MODE_PRIVATE

/**
 * Phone-side AI endpoint configuration used by the message summarizer.
 *
 * QMME deliberately ships no built-in key: when nothing is configured the
 * summarizer surfaces a "not configured" error and the settings page offers
 * the input fields. Values are plain OpenAI-compatible chat/completions
 * parameters (base URL may be https://api.example.com or .../v1).
 */
object AiSettings {
    private const val PREFS_NAME = "qmme_ai_settings"
    private const val KEY_BASE_URL = "ai_base_url"
    private const val KEY_API_KEY = "ai_api_key"
    private const val KEY_MODEL = "ai_model"

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

    /** Returns a normalized endpoint, or null when anything is missing. */
    fun resolve(context: Context): Endpoint? {
        val url = baseUrl(context)
        val key = apiKey(context)
        val model = model(context)
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
