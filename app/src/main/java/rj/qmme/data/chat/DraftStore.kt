package rj.qmme.data.chat

import android.content.Context

/** Local text drafts keyed by chat peer, adapted from QMCE's DraftStore. */
object DraftStore {
    private const val PREFS_NAME = "qmme_drafts"

    fun save(context: Context, peerUid: String, chatType: Int, text: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = key(peerUid, chatType)
        if (text.isBlank()) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, text).apply()
        }
    }

    fun load(context: Context, peerUid: String, chatType: Int): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key(peerUid, chatType), "")
            .orEmpty()

    fun clear(context: Context, peerUid: String, chatType: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(key(peerUid, chatType))
            .apply()
    }

    fun clearAll(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    private fun key(peerUid: String, chatType: Int) = "$chatType:$peerUid"
}
