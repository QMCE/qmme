package rj.qmme.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textview.MaterialTextView
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.EditText as HEditText
import com.highcapable.hikage.widget.android.widget.LinearLayout as HLinearLayout
import com.highcapable.hikage.widget.androidx.recyclerview.widget.RecyclerView as HRecyclerView
import com.highcapable.hikage.widget.com.google.android.material.appbar.MaterialToolbar as HMaterialToolbar
import com.highcapable.hikage.widget.com.google.android.material.button.MaterialButton as HMaterialButton
import com.highcapable.hikage.widget.com.google.android.material.card.MaterialCardView as HMaterialCardView
import com.highcapable.hikage.widget.com.google.android.material.progressindicator.CircularProgressIndicator as HCircularProgressIndicator
import com.highcapable.hikage.widget.com.google.android.material.textview.MaterialTextView as HMaterialTextView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import rj.qmme.R
import rj.qmme.viewmodel.ChatDetailViewModel

/** Phone-first Material 3 Expressive chat screen, composed with Hikage Views. */
class ChatDetailHikagable(
    private val context: Context,
    private val target: ChatDetailViewModel.ChatTarget,
    private val onBack: () -> Unit,
    private val onPickImage: () -> Unit,
    private val onOpenImage: (ChatDetailViewModel.UiImage) -> Unit,
) : HikageScreen {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var statusCard: MaterialCardView
    private lateinit var statusProgress: CircularProgressIndicator
    private lateinit var statusText: MaterialTextView
    private lateinit var loadOlderButton: MaterialButton
    private lateinit var recyclerView: RecyclerView
    private lateinit var input: EditText
    private lateinit var sendButton: MaterialButton
    private var messageActionHandler: ((ChatDetailViewModel.UiMessage) -> Unit)? = null
    private val adapter = MessageAdapter(
        onImageClick = onOpenImage,
        onMessageLongClick = { message -> messageActionHandler?.invoke(message) },
    )
    private var bound = false

    override val hikage: Hikage.Delegate<*> = Hikagable {
        HLinearLayout(
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
            HLinearLayout(
                lparams = LayoutParams(widthMatchParent = true, height = 0),
                init = { EdgeToEdgeInsets.applyTopInsetSpacer(this) },
            )
            toolbar = HMaterialToolbar(
                lparams = LayoutParams(widthMatchParent = true),
                init = {
                    title = target.title
                    subtitle = if (target.chatType == 2) "群聊" else "QQ 聊天"
                    navigationIcon = ContextCompat.getDrawable(context, R.drawable.ic_arrow_back)
                    setNavigationContentDescription("返回")
                    setNavigationOnClickListener { onBack() }
                    EdgeToEdgeInsets.applyHorizontalInsets(this)
                },
            )
            statusCard = HMaterialCardView(
                lparams = LayoutParams(widthMatchParent = true) {
                    leftMargin = dp(16)
                    rightMargin = dp(16)
                    topMargin = dp(6)
                    bottomMargin = dp(6)
                },
                init = {
                    radius = dp(28).toFloat()
                    strokeWidth = 0
                    cardElevation = 0f
                    setCardBackgroundColor(
                        MaterialColors.getColor(
                            this,
                            com.google.android.material.R.attr.colorSecondaryContainer,
                        ),
                    )
                },
            ) {
                HLinearLayout(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(18), dp(12), dp(18), dp(12))
                    },
                ) {
                    statusProgress = HCircularProgressIndicator(
                        lparams = LayoutParams(width = dp(22), height = dp(22)),
                        init = { isIndeterminate = true },
                    )
                    statusText = HMaterialTextView(
                        lparams = LayoutParams(width = 0, height = ViewGroup.LayoutParams.WRAP_CONTENT) {
                            weight = 1f
                            leftMargin = dp(12)
                        },
                        init = {
                            TextViewCompat.setTextAppearance(
                                this,
                                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                            )
                            setTextColor(
                                MaterialColors.getColor(
                                    this,
                                    com.google.android.material.R.attr.colorOnSecondaryContainer,
                                ),
                            )
                        },
                    )
                }
            }
            loadOlderButton = HMaterialButton(
                lparams = LayoutParams(
                    width = ViewGroup.LayoutParams.WRAP_CONTENT,
                    height = ViewGroup.LayoutParams.WRAP_CONTENT,
                ) {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dp(2)
                    bottomMargin = dp(2)
                },
                init = {
                    text = "加载更早消息"
                    isAllCaps = false
                },
            )
            recyclerView = HRecyclerView(
                lparams = LayoutParams(widthMatchParent = true, height = 0) { weight = 1f },
                init = {
                    layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
                    adapter = this@ChatDetailHikagable.adapter
                    itemAnimator = null
                    clipToPadding = false
                    setPadding(0, dp(8), 0, dp(12))
                },
            )
            HMaterialCardView(
                lparams = LayoutParams(widthMatchParent = true) {
                    leftMargin = dp(12)
                    rightMargin = dp(12)
                    topMargin = dp(6)
                    bottomMargin = dp(8)
                },
                init = {
                    radius = dp(30).toFloat()
                    strokeWidth = 0
                    cardElevation = 0f
                    setCardBackgroundColor(
                        MaterialColors.getColor(
                            this,
                            com.google.android.material.R.attr.colorSurfaceContainerHigh,
                        ),
                    )
                    EdgeToEdgeInsets.applyHorizontalInsets(this)
                },
            ) {
                HLinearLayout(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(16), dp(6), dp(6), dp(6))
                    },
                ) {
                    HMaterialButton(
                        lparams = LayoutParams(
                            width = ViewGroup.LayoutParams.WRAP_CONTENT,
                            height = dp(48),
                        ),
                        init = {
                            text = "图片"
                            isAllCaps = false
                            minWidth = 0
                            insetTop = 0
                            insetBottom = 0
                            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                            setTextColor(
                                MaterialColors.getColor(
                                    this,
                                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                                ),
                            )
                            setOnClickListener { onPickImage() }
                        },
                    )
                    input = HEditText(
                        lparams = LayoutParams(width = 0, height = ViewGroup.LayoutParams.WRAP_CONTENT) {
                            weight = 1f
                        },
                        init = {
                            hint = "发消息"
                            background = null
                            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
                            setHintTextColor(
                                MaterialColors.getColor(
                                    this,
                                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                                ),
                            )
                            maxLines = 5
                            minLines = 1
                            imeOptions = EditorInfo.IME_ACTION_SEND
                            setSingleLine(false)
                            setPadding(0, dp(8), dp(8), dp(8))
                            setOnEditorActionListener { _, actionId, _ ->
                                if (actionId == EditorInfo.IME_ACTION_SEND) {
                                    performSend()
                                    true
                                } else {
                                    false
                                }
                            }
                        },
                    )
                    sendButton = HMaterialButton(
                        lparams = LayoutParams(
                            width = ViewGroup.LayoutParams.WRAP_CONTENT,
                            height = dp(48),
                        ),
                        init = {
                            text = "发送"
                            isAllCaps = false
                            insetTop = 0
                            insetBottom = 0
                            setOnClickListener { performSend() }
                        },
                    )
                }
            }
            HLinearLayout(
                lparams = LayoutParams(widthMatchParent = true, height = 0),
                init = { EdgeToEdgeInsets.applyBottomInsetSpacer(this) },
            )
        }
    }

    fun bind(owner: LifecycleOwner, viewModel: ChatDetailViewModel, accountUin: String) {
        if (bound) return
        bound = true
        boundViewModel = viewModel
        messageActionHandler = { message -> showMessageActions(viewModel, message) }
        statusCard.setOnClickListener { viewModel.retry() }
        statusCard.contentDescription = "聊天状态，点按重试"
        loadOlderButton.setOnClickListener { viewModel.loadOlder() }
        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.messages.collectLatest { messages ->
                        val previous = adapter.currentList
                        val shouldFollowBottom = previous.isEmpty() ||
                            previous.lastOrNull()?.stableId != messages.lastOrNull()?.stableId
                        adapter.submitList(messages) {
                            if (shouldFollowBottom && messages.isNotEmpty()) {
                                recyclerView.scrollToPosition(messages.lastIndex)
                            }
                        }
                    }
                }
                launch {
                    viewModel.statusText.collectLatest { text ->
                        statusText.text = text
                        statusCard.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
                    }
                }
                launch {
                    viewModel.loading.collectLatest { loading ->
                        statusProgress.visibility = if (loading) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.loadingOlder.collectLatest { loading ->
                        loadOlderButton.isEnabled = !loading
                        loadOlderButton.text = if (loading) "正在加载…" else "加载更早消息"
                    }
                }
                launch {
                    viewModel.hasOlder.collectLatest { hasOlder ->
                        loadOlderButton.visibility = if (hasOlder) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.sending.collectLatest { sending ->
                        sendButton.isEnabled = !sending
                        input.isEnabled = !sending
                    }
                }
            }
        }
        viewModel.openChat(target, accountUin)
    }

    private fun showMessageActions(
        viewModel: ChatDetailViewModel,
        message: ChatDetailViewModel.UiMessage,
    ) {
        data class Action(val label: String, val run: () -> Unit)

        val actions = buildList {
            if (message.text.isNotBlank()) {
                add(
                    Action("复制") {
                        val clipboard = context.getSystemService(ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(
                            ClipData.newPlainText("QQ 消息", message.text),
                        )
                        Toast.makeText(context, "已复制消息", Toast.LENGTH_SHORT).show()
                    },
                )
            }
            if (message.outgoing && message.messageId > 0L) {
                add(
                    Action("撤回") {
                        MaterialAlertDialogBuilder(context)
                            .setTitle("撤回这条消息？")
                            .setMessage("撤回后，聊天中的其他人将无法继续看到原消息。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("撤回") { _, _ -> viewModel.recallMessage(message) }
                            .show()
                    },
                )
            }
            if (message.messageId > 0L) {
                add(
                    Action("删除") {
                        MaterialAlertDialogBuilder(context)
                            .setTitle("删除这条消息？")
                            .setMessage("只会从当前聊天记录中删除，无法撤回对方已经收到的内容。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("删除") { _, _ -> viewModel.deleteMessage(message) }
                            .show()
                    },
                )
            }
            if (message.outgoing && message.messageId > 0L && message.sendStatus != 2) {
                add(Action("重发") { viewModel.resendMessage(message) })
            }
        }
        if (actions.isEmpty()) return
        MaterialAlertDialogBuilder(context)
            .setTitle("消息操作")
            .setItems(actions.map(Action::label).toTypedArray()) { _, which ->
                actions.getOrNull(which)?.run?.invoke()
            }
            .show()
    }

    private fun performSend() {
        val viewModel = boundViewModel ?: return
        val value = input.text?.toString().orEmpty()
        if (viewModel.sendText(value)) input.text?.clear()
    }

    private var boundViewModel: ChatDetailViewModel? = null

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
