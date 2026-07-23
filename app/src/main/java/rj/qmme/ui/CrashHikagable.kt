package rj.qmme.ui

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import androidx.core.widget.TextViewCompat
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.LinearLayout as HLinearLayout
import com.highcapable.hikage.widget.android.widget.ScrollView as HScrollView
import com.highcapable.hikage.widget.com.google.android.material.textview.MaterialTextView as HMaterialTextView
import rj.qmme.CrashReport

/** M3 crash report screen, built entirely as a native Hikage hierarchy. */
class CrashHikagable(
    private val context: Context,
    private val report: CrashReport,
) : HikageScreen {
    override val hikage: Hikage.Delegate<*> = Hikagable {
        HScrollView(
            lparams = LayoutParams(matchParent = true),
            init = {
                isFillViewport = true
                clipToPadding = false
                EdgeToEdgeInsets.applyContentInsets(this)
            },
        ) {
            HLinearLayout(
                lparams = LayoutParams(widthMatchParent = true),
                init = {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(16), dp(16), dp(24))
                },
            ) {
                HMaterialTextView(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        text = "应用发生崩溃"
                        gravity = Gravity.CENTER_HORIZONTAL
                        TextViewCompat.setTextAppearance(
                            this,
                            com.google.android.material.R.style.TextAppearance_Material3_TitleLarge,
                        )
                    },
                )
                HMaterialTextView(
                    lparams = LayoutParams(widthMatchParent = true) {
                        topMargin = dp(16)
                    },
                    init = {
                        text = report.asDisplayText()
                        setTextIsSelectable(true)
                        TextViewCompat.setTextAppearance(
                            this,
                            com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
                        )
                    },
                )
            }
        }
    }

    private fun CrashReport.asDisplayText() = buildString {
        appendLine("报告编号: $id")
        appendLine("进程: $process")
        appendLine("线程: $thread")
        appendLine("错误: $error")
        appendLine()
        append(stacktrace)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
