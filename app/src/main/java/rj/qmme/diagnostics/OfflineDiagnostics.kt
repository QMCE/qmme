package rj.qmme.diagnostics

import android.app.Application
import android.content.Context
import android.os.Process
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small crash-safe breadcrumb file for the login → runtime → kernel transition.
 * Logcat is too small on the test device and native crashes can overwrite the
 * account callback that happened immediately before the tombstone.
 *
 * Never write tickets, passwords or a full UIN here.
 */
object OfflineDiagnostics {
    private const val TAG = "QMME-Offline"
    private const val FILE_NAME = "qmme-offline-diagnostics.log"
    private const val MAX_BYTES = 256 * 1024L
    private val lock = Any()
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSSZ", Locale.US)

    fun record(context: Context?, event: String, details: String = "") {
        val safeContext = context?.applicationContext ?: return
        val line = buildString {
            append(formatter.format(Date()))
            append(" pid=").append(Process.myPid())
            append(" process=").append(runCatching { Application.getProcessName() }.getOrDefault("unknown"))
            append(" thread=").append(Thread.currentThread().name.replace(' ', '_'))
            append(" event=").append(sanitize(event))
            if (details.isNotBlank()) append(' ').append(sanitize(details))
            append('\n')
        }
        Log.w(TAG, line.trimEnd())
        synchronized(lock) {
            runCatching {
                val target = File(safeContext.filesDir, FILE_NAME)
                if (target.length() > MAX_BYTES) {
                    val backup = File(safeContext.filesDir, "$FILE_NAME.previous")
                    backup.delete()
                    target.renameTo(backup)
                }
                target.appendText(line)
            }.onFailure { Log.w(TAG, "failed to persist breadcrumb", it) }
        }
    }

    private fun sanitize(value: String): String = value
        .replace('\n', '_')
        .replace('\r', '_')
}
