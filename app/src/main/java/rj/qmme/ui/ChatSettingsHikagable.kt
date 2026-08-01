package rj.qmme.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.listitem.ListItemCardView
import com.google.android.material.listitem.ListItemLayout
import com.google.android.material.textview.MaterialTextView
import com.highcapable.betterandroid.ui.extension.component.base.getDrawableCompat
import com.highcapable.betterandroid.ui.extension.view.toast
import com.highcapable.hikage.annotation.Hikagable
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.androidx.core.widget.NestedScrollView
import com.highcapable.hikage.widget.android.widget.LinearLayout
import com.highcapable.hikage.widget.com.google.android.material.appbar.MaterialToolbar
import rj.qmme.R
import rj.qmme.ui.hikage.settingsGroup

/** Per-session chat settings (pin, mute, members / profile). */
class ChatSettingsHikagable(
    private val context: Context,
    private val targetTitle: String,
    private val isGroup: Boolean,
    private val onBack: () -> Unit,
    private val onSetTop: (enabled: Boolean, done: (Boolean, String?) -> Unit) -> Unit,
    private val onSetMuted: (muted: Boolean, done: (Boolean, String?) -> Unit) -> Unit,
    private val onOpenMembers: (() -> Unit)? = null,
    private val onOpenProfile: (() -> Unit)? = null,
) : HikageScreen {
    private var pinned = false
    private var muted = false
    private lateinit var pinSubtitleView: MaterialTextView
    private lateinit var muteSubtitleView: MaterialTextView
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
                        title = "会话设置"
                        subtitle = targetTitle
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
                        buildToggleGroup()
                        settingsGroup {
                            if (isGroup) {
                                row(
                                    icon = context.getDrawableCompat(R.drawable.ic_group),
                                    title = "群成员",
                                    subtitle = "查看本群成员列表",
                                    onClick = { onOpenMembers?.invoke() },
                                )
                            } else {
                                row(
                                    icon = context.getDrawableCompat(R.drawable.ic_account_circle),
                                    title = "查看资料",
                                    subtitle = "打开联系人资料页",
                                    onClick = { onOpenProfile?.invoke() },
                                )
                            }
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
    private fun Hikage.Performer<LinearLayout.LayoutParams>.buildToggleGroup() {
        LinearLayout(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = dp(8)
            },
            init = {
                orientation = LinearLayout.VERTICAL
                val pinRow = createToggleRow(
                    icon = context.getDrawableCompat(R.drawable.ic_push_pin),
                    title = "置顶聊天",
                    subtitle = toggleSubtitle(pinned),
                    onClick = { togglePinned() },
                )
                pinSubtitleView = pinRow.subtitle
                addView(pinRow.layout)

                val muteRow = createToggleRow(
                    icon = context.getDrawableCompat(R.drawable.ic_notifications),
                    title = "消息免打扰",
                    subtitle = toggleSubtitle(muted),
                    onClick = { toggleMuted() },
                )
                muteSubtitleView = muteRow.subtitle
                addView(muteRow.layout)

                pinRow.layout.updateAppearance(0, 2)
                muteRow.layout.updateAppearance(1, 2)
            },
        )
    }

    private data class ToggleRow(
        val layout: ListItemLayout,
        val subtitle: MaterialTextView,
    )

    private fun createToggleRow(
        icon: Drawable?,
        title: String,
        subtitle: String,
        onClick: () -> Unit,
    ): ToggleRow {
        val listItem = ListItemLayout(context)
        val card = ListItemCardView(
            context,
            null,
            com.google.android.material.R.attr.listItemCardViewSegmentedStyle,
        ).apply {
            setContentPadding(0, 0, 0, 0)
            isSwipeEnabled = false
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val rowLine = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(20), dp(14), dp(20), dp(14))
        }

        val contentColor = MaterialColors.getColor(
            listItem,
            com.google.android.material.R.attr.colorOnSurface,
        )
        val supportColor = MaterialColors.getColor(
            listItem,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
        )

        if (icon != null) {
            val iconView = com.google.android.material.imageview.ShapeableImageView(context).apply {
                setImageDrawable(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(contentColor)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            rowLine.addView(
                iconView,
                LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) },
            )
        }

        val textColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        textColumn.addView(
            MaterialTextView(context).apply {
                text = title
                TextViewCompat.setTextAppearance(
                    this,
                    com.google.android.material.R.style.TextAppearance_Material3_BodyLarge,
                )
                setTextColor(contentColor)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
        )
        val subtitleView = MaterialTextView(context).apply {
            text = subtitle
            TextViewCompat.setTextAppearance(
                this,
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
            )
            setTextColor(supportColor)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        textColumn.addView(
            subtitleView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(2) },
        )
        rowLine.addView(
            textColumn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        content.addView(rowLine)
        card.addView(
            content,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        listItem.addView(
            card,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        return ToggleRow(listItem, subtitleView)
    }

    private fun toggleSubtitle(enabled: Boolean): String =
        if (enabled) "已开启" else "已关闭"

    private fun togglePinned() {
        val next = !pinned
        onSetTop(next) { success, error ->
            if (success) {
                pinned = next
                pinSubtitleView.text = toggleSubtitle(pinned)
                context.toast(if (next) "已置顶" else "已取消置顶")
            } else {
                context.toast(error?.takeIf { it.isNotBlank() } ?: "置顶设置失败")
            }
        }
    }

    private fun toggleMuted() {
        val next = !muted
        onSetMuted(next) { success, error ->
            if (success) {
                muted = next
                muteSubtitleView.text = toggleSubtitle(muted)
                context.toast(if (next) "已开启免打扰" else "已关闭免打扰")
            } else {
                context.toast(error?.takeIf { it.isNotBlank() } ?: "免打扰设置失败")
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
