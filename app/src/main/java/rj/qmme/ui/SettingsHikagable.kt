package rj.qmme.ui

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import com.highcapable.betterandroid.ui.extension.component.base.getDrawableCompat
import com.highcapable.betterandroid.ui.extension.view.textToString
import com.highcapable.betterandroid.ui.extension.view.toast
import com.highcapable.hikage.annotation.Hikagable
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.androidx.core.widget.NestedScrollView
import com.highcapable.hikage.widget.android.widget.LinearLayout
import com.highcapable.hikage.widget.com.google.android.material.appbar.MaterialToolbar
import com.highcapable.hikage.widget.com.google.android.material.textview.MaterialTextView
import rj.qmme.R
import rj.qmme.data.AiSettings
import rj.qmme.ui.hikage.settingsGroup

/** Phone settings hub with interaction, security, data, and about sections. */
class SettingsHikagable(
    private val context: Context,
    private val onBack: () -> Unit,
    private val onOpenAbout: () -> Unit,
    private val onClearDrafts: () -> Unit,
    private var enterToSend: Boolean,
    private val onEnterToSendChanged: (Boolean) -> Unit,
    private var confirmLogout: Boolean,
    private val onConfirmLogoutChanged: (Boolean) -> Unit,
    private var agentEnabled: Boolean,
    private val onAgentEnabledChanged: (Boolean) -> Unit,
) : HikageScreen {
    private lateinit var enterToSendSwitch: MaterialSwitch
    private lateinit var confirmLogoutSwitch: MaterialSwitch
    private lateinit var agentEnabledSwitch: MaterialSwitch
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
                        title = "设置"
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
                        buildSectionLabel("交互")
                        buildSwitchRow(
                            title = "回车发送",
                            subtitle = "在聊天输入框中按回车键直接发送消息",
                            initial = enterToSend,
                            onAssign = { switch ->
                                enterToSendSwitch = switch
                                switch.setOnCheckedChangeListener(enterToSendListener)
                            },
                        )
                        buildSectionLabel("安全")
                        buildSwitchRow(
                            title = "退出前确认",
                            subtitle = "退出登录或强制退出前显示确认页面",
                            initial = confirmLogout,
                            onAssign = { switch ->
                                confirmLogoutSwitch = switch
                                switch.setOnCheckedChangeListener(confirmLogoutListener)
                            },
                        )
                        buildSectionLabel("AI 摘要")
                        settingsGroup {
                            row(
                                icon = context.getDrawableCompat(R.drawable.ic_info),
                                title = "AI 服务设置",
                                subtitle = aiSubtitle(),
                                onClick = { showAiEndpointDialog() },
                            )
                        }
                        buildSwitchRow(
                            title = "AI 助手（Fluoxetine）",
                            subtitle = "在「我的」页提供可调用 QQ 工具的 AI 对话助手",
                            initial = agentEnabled,
                            onAssign = { switch ->
                                agentEnabledSwitch = switch
                                switch.setOnCheckedChangeListener(agentEnabledListener)
                            },
                        )
                        buildSectionLabel("数据")
                        settingsGroup {
                            row(
                                icon = context.getDrawableCompat(R.drawable.ic_clear),
                                title = "清除本地草稿",
                                subtitle = "删除所有会话中未发送的草稿文本",
                                destructive = true,
                                onClick = { confirmClearDrafts() },
                            )
                        }
                        buildSectionLabel("关于")
                        settingsGroup {
                            row(
                                icon = context.getDrawableCompat(R.drawable.ic_info),
                                title = "关于 QMME",
                                subtitle = "版本信息与免责说明",
                                onClick = onOpenAbout,
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

    fun refreshToggles(enterToSend: Boolean, confirmLogout: Boolean) {
        this.enterToSend = enterToSend
        this.confirmLogout = confirmLogout
        if (::enterToSendSwitch.isInitialized) {
            enterToSendSwitch.setOnCheckedChangeListener(null)
            enterToSendSwitch.isChecked = enterToSend
            enterToSendSwitch.setOnCheckedChangeListener(enterToSendListener)
        }
        if (::confirmLogoutSwitch.isInitialized) {
            confirmLogoutSwitch.setOnCheckedChangeListener(null)
            confirmLogoutSwitch.isChecked = confirmLogout
            confirmLogoutSwitch.setOnCheckedChangeListener(confirmLogoutListener)
        }
    }

    private val enterToSendListener =
        android.widget.CompoundButton.OnCheckedChangeListener { _, checked ->
            enterToSend = checked
            onEnterToSendChanged(checked)
        }

    private val confirmLogoutListener =
        android.widget.CompoundButton.OnCheckedChangeListener { _, checked ->
            confirmLogout = checked
            onConfirmLogoutChanged(checked)
        }

    private val agentEnabledListener =
        android.widget.CompoundButton.OnCheckedChangeListener { _, checked ->
            agentEnabled = checked
            onAgentEnabledChanged(checked)
        }

    private fun confirmClearDrafts() {
        MaterialAlertDialogBuilder(context)
            .setTitle("清除本地草稿")
            .setMessage("将删除所有会话的未发送草稿，此操作不可恢复。")
            .setPositiveButton("清除") { _, _ -> onClearDrafts() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun aiSubtitle(): String = when {
        AiSettings.isBuiltin(context) -> "内置免费端点 big-pickle，开箱即用；可自定义覆盖"
        AiSettings.resolve(context) != null -> "已自定义：${AiSettings.model(context)}"
        else -> "自定义端点不完整，补全三项或清空以回退内置"
    }

    /** Plain Views on purpose: this dialog lives outside the Hikage tree. */
    private fun showAiEndpointDialog() {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val urlField = endpointField("接口地址", AiSettings.baseUrl(context), "https://api.example.com/v1")
        val keyField = endpointField("API Key", AiSettings.apiKey(context), "sk-…")
        val modelField = endpointField("模型名称", AiSettings.model(context), "deepseek-chat")
        container.addView(urlField)
        container.addView(keyField)
        container.addView(modelField)
        MaterialAlertDialogBuilder(context)
            .setTitle("AI 服务设置")
            .setMessage("填写 OpenAI 兼容的 chat/completions 接口，聊天页即可使用 AI 摘要。密钥只保存在本机。")
            .setView(container)
            .setNegativeButton("取消", null)
            .setPositiveButton("保存") { _, _ ->
                AiSettings.setBaseUrl(context, urlField.editText?.textToString().orEmpty())
                AiSettings.setApiKey(context, keyField.editText?.textToString().orEmpty())
                AiSettings.setModel(context, modelField.editText?.textToString().orEmpty())
                context.toast("AI 设置已保存")
            }
            .show()
    }

    private fun endpointField(label: String, initial: String, hint: String): TextInputLayout {
        val layout = TextInputLayout(context).apply {
            this.hint = label
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(12) }
        }
        layout.addView(
            TextInputEditText(layout.context).apply {
                setText(initial)
                this.hint = hint
                isSingleLine = true
            },
        )
        return layout
    }

    @Hikagable
    private fun Hikage.Performer<LinearLayout.LayoutParams>.buildSectionLabel(text: String) {
        MaterialTextView(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = dp(16)
                bottomMargin = dp(6)
                marginStart = dp(16)
            },
            init = {
                this.text = text
                TextViewCompat.setTextAppearance(
                    this,
                    com.google.android.material.R.style.TextAppearance_Material3_LabelLarge_Emphasized,
                )
                setTextColor(
                    MaterialColors.getColor(
                        this,
                        androidx.appcompat.R.attr.colorPrimary,
                    ),
                )
            },
        )
    }

    @Hikagable
    private fun Hikage.Performer<LinearLayout.LayoutParams>.buildSwitchRow(
        title: String,
        subtitle: String,
        initial: Boolean,
        onAssign: (MaterialSwitch) -> Unit,
    ) {
        LinearLayout(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = dp(4)
            },
            init = {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(14), dp(20), dp(14))
                setBackgroundColor(
                    MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorSurfaceContainerLow,
                    ),
                )

                val textColumn = android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                textColumn.addView(
                    MaterialTextView(context).apply {
                        this.text = title
                        TextViewCompat.setTextAppearance(
                            this,
                            com.google.android.material.R.style.TextAppearance_Material3_BodyLarge,
                        )
                    },
                )
                textColumn.addView(
                    MaterialTextView(context).apply {
                        this.text = subtitle
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
                    android.widget.LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { topMargin = dp(2) },
                )
                addView(textColumn)

                val switch = MaterialSwitch(context).apply {
                    isChecked = initial
                }
                onAssign(switch)
                addView(switch)
            },
        )
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
