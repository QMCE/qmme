package rj.qmme.data

import android.content.Context

/** Lightweight phone-side preferences (not Wear-specific appearance knobs). */
object AppSettings {
    private const val PREFS_NAME = "qmme_app_settings"
    private const val KEY_ENTER_TO_SEND = "enter_to_send"
    private const val KEY_CONFIRM_LOGOUT = "confirm_logout"
    private const val KEY_NOTIFY_ENABLED = "notify_enabled"
    private const val KEY_NOTIFY_C2C = "notify_c2c"
    private const val KEY_NOTIFY_GROUP = "notify_group"
    private const val KEY_NOTIFY_CONTACT = "notify_contact"

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

    /** Master switch for all system notifications (message + contact system). */
    fun notifyEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFY_ENABLED, true)

    fun setNotifyEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFY_ENABLED, enabled).apply()
    }

    fun notifyC2c(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFY_C2C, true)

    fun setNotifyC2c(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFY_C2C, enabled).apply()
    }

    fun notifyGroup(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFY_GROUP, true)

    fun setNotifyGroup(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFY_GROUP, enabled).apply()
    }

    /** Friend requests / group system notices posted by the contact notifier. */
    fun notifyContact(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFY_CONTACT, true)

    fun setNotifyContact(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFY_CONTACT, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
