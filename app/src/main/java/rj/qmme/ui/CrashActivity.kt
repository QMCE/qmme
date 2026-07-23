package rj.qmme.ui

import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import rj.qmme.CrashCatcher

/** Plain-View crash screen (no Jetpack Compose). Shows the latest captured crash report. */
class CrashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val report = CrashCatcher.readLatestReport(this, intent)

        val padding = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val title = TextView(this).apply {
            text = "应用发生崩溃"
            textSize = 18f
            gravity = Gravity.CENTER
        }
        val details = TextView(this).apply {
            textSize = 12f
            setTextIsSelectable(true)
            movementMethod = ScrollingMovementMethod()
            text = buildString {
                appendLine("报告编号: ${report.id}")
                appendLine("进程: ${report.process}")
                appendLine("线程: ${report.thread}")
                appendLine("错误: ${report.error}")
                appendLine()
                append(report.stacktrace)
            }
        }
        root.addView(
            title,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            ScrollView(this).apply { addView(details) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        setContentView(root)
    }
}
