package rj.qmme.data

import android.content.Context

/** Lightweight phone-side preferences (not Wear-specific appearance knobs). */
object AppSettings {
    private const val PREFS_NAME = "qmme_app_settings"
    private const val KEY_ENTER_TO_SEND = "enter_to_send"
    private const val KEY_CONFIRM_LOGOUT = "confirm_logout"

    fun enterToSend(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENTER_TO_SEND, false)

    fun setEnterToSend(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENTER_TO_SEND, enabled).apply()
    }

    fun confirmLogout(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONFIRM_LOGOUT, true)

    fun setConfirmLogout(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONFIRM_LOGOUT, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
