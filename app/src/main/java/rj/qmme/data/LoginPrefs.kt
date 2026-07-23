package rj.qmme.data

import android.content.Context
import com.tencent.qphone.base.remote.SimpleAccount

/** App-owned durable login record used across the post-login process restart. */
object LoginPrefs {
    private const val PREFS_NAME = "qmme_login"
    private const val KEY_ACCOUNT = "account"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun loadAccount(context: Context): SimpleAccount? {
        val stored = prefs(context).getString(KEY_ACCOUNT, null) ?: return null
        return runCatching { SimpleAccount.parseSimpleAccount(stored) }
            .getOrNull()
            ?.takeIf { it.isLogined && it.uin.isNotBlank() }
    }

    /** Synchronous by design: the login flow schedules process termination immediately after. */
    fun saveAccount(context: Context, account: SimpleAccount): Boolean {
        if (account.uin.isBlank()) return false
        return prefs(context).edit()
            .putString(KEY_ACCOUNT, account.toStoreString())
            .commit()
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_ACCOUNT).apply()
    }

    fun hasAccount(context: Context): Boolean = loadAccount(context) != null
}
