package rj.qmme.ui

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.textview.MaterialTextView
import com.highcapable.betterandroid.ui.extension.component.base.getDrawableCompat
import com.highcapable.hikage.annotation.Hikagable
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.androidx.core.widget.NestedScrollView
import com.highcapable.hikage.widget.android.widget.LinearLayout
import com.highcapable.hikage.widget.com.google.android.material.appbar.MaterialToolbar
import com.highcapable.hikage.widget.com.google.android.material.textview.MaterialTextView
import rj.qmme.BuildConfig
import rj.qmme.R
import rj.qmme.ui.hikage.settingsGroup

/** About screen with app version and legal notes. */
class AboutHikagable(
    private val context: Context,
    private val onBack: () -> Unit,
) : HikageScreen {
    private var cachedHikage: Hikage.Delegate<*>? = null

    override val hikage
        get() = cachedHikage ?: Hikagable {
            LinearLayout(
                lparams = LayoutParams(matchParent = true),
                init = {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(
                        MaterialColors.getColor(
                            this,
                            com.google.android.material.R.attr.colorSurface,
                        ),
                    )
                },
            ) {
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true, height = 0),
                    init = { EdgeToEdgeInsets.applyTopInsetSpacer(this) },
                )
                MaterialToolbar(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        title = "关于"
                        navigationIcon = drawableResource(R.drawable.ic_arrow_back)
                        setNavigationContentDescription("返回")
                        setNavigationOnClickListener { onBack() }
                        EdgeToEdgeInsets.applyHorizontalInsets(this)
                    },
                )
                NestedScrollView(
                    lparams = LayoutParams(widthMatchParent = true, height = 0) { weight = 1f },
                    init = {
                        isFillViewport = true
                        clipToPadding = false
                    },
                ) {
                    LinearLayout(
                        lparams = LayoutParams(widthMatchParent = true),
                        init = {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dp(12), dp(8), dp(12), dp(24))
                        },
                    ) {
                        buildHeader()
                        settingsGroup {
                            row(
                                icon = context.getDrawableCompat(R.drawable.ic_info),
                                title = "开源协议",
                                subtitle = "本项目基于开源组件构建，具体许可请参阅各依赖项声明。",
                            )
                            row(
                                icon = context.getDrawableCompat(R.drawable.ic_warning),
                                title = "免责说明",
                                subtitle = "QMME 为非官方客户端，与腾讯公司无任何关联。使用本软件的风险由用户自行承担。",
                            )
                        }
                    }
                }
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true, height = 0),
                    init = { EdgeToEdgeInsets.applyBottomInsetSpacer(this) },
                )
            }
        }.also { cachedHikage = it }

    @Hikagable
    private fun Hikage.Performer<LinearLayout.LayoutParams>.buildHeader() {
        LinearLayout(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = dp(8)
                bottomMargin = dp(16)
            },
            init = {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(16), dp(24), dp(16), dp(16))
            },
        ) {
            MaterialTextView(
                lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT),
                init = {
                    text = "QMME"
                    TextViewCompat.setTextAppearance(
                        this,
                        com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium_Emphasized,
                    )
                },
            )
            MaterialTextView(
                lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT) {
                    topMargin = dp(4)
                },
                init = {
                    text = "版本 ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
                    TextViewCompat.setTextAppearance(
                        this,
                        com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                    )
                    setTextColor(
                        MaterialColors.getColor(
                            this,
                            com.google.android.material.R.attr.colorOnSurfaceVariant,
                        ),
                    )
                },
            )
            MaterialTextView(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = dp(16)
                },
                init = {
                    text = "非官方手机 QQ 客户端，基于手表 QQ NT 内核"
                    TextViewCompat.setTextAppearance(
                        this,
                        com.google.android.material.R.style.TextAppearance_Material3_BodyLarge,
                    )
                    gravity = Gravity.CENTER
                },
            )
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
