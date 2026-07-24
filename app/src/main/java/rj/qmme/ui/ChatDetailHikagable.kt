package rj.qmme.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
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
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textview.MaterialTextView
import com.highcapable.hikage.annotation.Hikagable as HikagableAnnotation
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.EditText as HEditText
import com.highcapable.hikage.widget.android.widget.FrameLayout as HFrameLayout
import com.highcapable.hikage.widget.android.widget.LinearLayout as HLinearLayout
import com.highcapable.hikage.widget.androidx.recyclerview.widget.RecyclerView as HRecyclerView
import com.highcapable.hikage.widget.androidx.swiperefreshlayout.widget.SwipeRefreshLayout as HSwipeRefreshLayout
import com.highcapable.hikage.widget.com.google.android.material.appbar.MaterialToolbar as HMaterialToolbar
import com.highcapable.hikage.widget.com.google.android.material.button.MaterialButton as HMaterialButton
import com.highcapable.hikage.widget.com.google.android.material.card.MaterialCardView as HMaterialCardView
import com.highcapable.hikage.widget.com.google.android.material.imageview.ShapeableImageView as HShapeableImageView
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
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var input: EditText
    private lateinit var imageButton: MaterialButton
    private lateinit var sendButton: MaterialButton
    private lateinit var attachmentPanel: View
    private var panelOpen = false
    private var panelAnimator: ValueAnimator? = null
    private var messageActionHandler: ((ChatDetailViewModel.UiMessage) -> Unit)? = null
    private val adapter = MessageAdapter(
        isGroup = target.chatType == 2,
        onImageClick = onOpenImage,
        onMessageLongClick = { message -> messageActionHandler?.invoke(message) },
    )
    private var bound = false
    private var lastLoading = false
    private var boundViewModel: ChatDetailViewModel? = null
    private var cachedHikage: Hikage.Delegate<*>? = null

    override val hikage
        get() = cachedHikage ?: Hikagable {
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
                buildToolbar()
                buildStatusCard()
                buildMessageArea()
                buildComposer()
                buildAttachmentPanel()
                HLinearLayout(
                    lparams = LayoutParams(widthMatchParent = true, height = 0),
                    init = { EdgeToEdgeInsets.applyBottomInsetSpacerWithIme(this) },
                )
            }
        }.also { cachedHikage = it }

    @HikagableAnnotation
    private fun Hikage.Performer<LinearLayout.LayoutParams>.buildToolbar(): MaterialToolbar {
        toolbar = HMaterialToolbar(
            lparams = LayoutParams(widthMatchParent = true),
            init = {
                navigationIcon = ContextCompat.getDrawable(context, R.drawable.ic_arrow_back)
                setNavigationContentDescription("返回")
                setNavigationOnClickListener { onBack() }
                setContentInsetStartWithNavigation(0)
                logo = ContextCompat.getDrawable(context, R.drawable.ic_account_circle)
                title = target.title
                subtitle = if (target.chatType == 2) "群聊" else "QQ 聊天"
                setTitleTextAppearance(
                    context,
                    com.google.android.material.R.style.TextAppearance_Material3_TitleMedium,
                )
                setSubtitleTextAppearance(
                    context,
                    com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
                )
                EdgeToEdgeInsets.applyHorizontalInsets(this)
            },
        )
        return toolbar
    }

    @HikagableAnnotation
    private fun Hikage.Performer<LinearLayout.LayoutParams>.buildStatusCard(): MaterialCardView {
        statusCard = HMaterialCardView(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = dp(12)
                leftMargin = dp(12)
                rightMargin = dp(12)
                bottomMargin = dp(4)
            },
        ) {
            HLinearLayout(
                lparams = LayoutParams(matchParent = true),
                init = {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                },
            ) {
                statusProgress = HCircularProgressIndicator(
                    lparams = LayoutParams(width = dp(20), height = dp(20)) {
                        rightMargin = dp(12)
                    },
                    init = {
                        isIndeterminate = true
                        visibility = View.GONE
                    },
                )
                statusText = HMaterialTextView(
                    lparams = LayoutParams(width = 0, height = ViewGroup.LayoutParams.WRAP_CONTENT) {
                        weight = 1f
                    },
                    init = {
                        TextViewCompat.setTextAppearance(
                            this,
                            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                        )
                    },
                )
            }
        }
        return statusCard
    }

    @HikagableAnnotation
    private fun Hikage.Performer<LinearLayout.LayoutParams>.buildMessageArea(): FrameLayout =
        HFrameLayout(
            lparams = LayoutParams(widthMatchParent = true, height = 0) { weight = 1f },
        ) {
            swipeRefresh = HSwipeRefreshLayout(
                lparams = LayoutParams(matchParent = true),
            ) {
                recyclerView = HRecyclerView(
                    lparams = LayoutParams(matchParent = true),
                    init = {
                        layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
                        adapter = this@ChatDetailHikagable.adapter
                        itemAnimator = null
                        clipToPadding = false
                        overScrollMode = View.OVER_SCROLL_NEVER
                        setPadding(0, dp(8), 0, dp(12))
                    },
                )
            }
            emptyView = HLinearLayout(
                lparams = LayoutParams(
                    width = ViewGroup.LayoutParams.WRAP_CONTENT,
                    height = ViewGroup.LayoutParams.WRAP_CONTENT,
                ) { gravity = Gravity.CENTER },
                init = {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                    visibility = View.GONE
                },
            ) {
                HShapeableImageView(
                    lparams = LayoutParams(width = dp(48), height = dp(48)),
                    init = {
                        setImageResource(R.drawable.ic_chat)
                        alpha = 0.5f
                        imageTintList = ColorStateList.valueOf(
                            MaterialColors.getColor(
                                this,
                                com.google.android.material.R.attr.colorOnSurfaceVariant,
                            ),
                        )
                    },
                )
                HMaterialTextView(
                    lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT) {
                        topMargin = dp(8)
                    },
                    init = {
                        text = "还没有消息，打个招呼吧"
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
            }
        }

    @HikagableAnnotation
    private fun Hikage.Performer<LinearLayout.LayoutParams>.buildComposer(): MaterialCardView =
        HMaterialCardView(
            lparams = LayoutParams(widthMatchParent = true) {
                leftMargin = dp(12)
                rightMargin = dp(12)
                topMargin = dp(6)
                bottomMargin = dp(8)
            },
            init = {
                radius = dp(28).toFloat()
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
                    gravity = Gravity.BOTTOM
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                },
            ) {
                imageButton = HMaterialButton(
                    lparams = LayoutParams(width = dp(48), height = dp(48)) {
                        gravity = Gravity.BOTTOM
                    },
                    init = {
                        icon = ContextCompat.getDrawable(context, R.drawable.ic_add)
                        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                        iconPadding = 0
                        text = ""
                        minWidth = 0
                        minimumWidth = 0
                        insetTop = 0
                        insetBottom = 0
                        contentDescription = "更多"
                        backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                        iconTint = ColorStateList.valueOf(
                            MaterialColors.getColor(
                                this,
                                com.google.android.material.R.attr.colorOnSurfaceVariant,
                            ),
                        )
                        setOnClickListener { toggleAttachmentPanel() }
                    },
                )
                input = HEditText(
                    lparams = LayoutParams(width = 0, height = ViewGroup.LayoutParams.WRAP_CONTENT) {
                        weight = 1f
                        gravity = Gravity.CENTER_VERTICAL
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
                        setPadding(dp(6), dp(12), dp(6), dp(12))
                        setOnEditorActionListener { _, actionId, _ ->
                            if (actionId == EditorInfo.IME_ACTION_SEND) {
                                performSend()
                                true
                            } else {
                                false
                            }
                        }
                        setOnClickListener { hideAttachmentPanel() }
                    },
                )
                sendButton = HMaterialButton(
                    lparams = LayoutParams(width = dp(48), height = dp(48)) {
                        leftMargin = dp(4)
                        gravity = Gravity.BOTTOM
                    },
                    init = {
                        icon = ContextCompat.getDrawable(context, R.drawable.ic_send)
                        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                        iconPadding = 0
                        text = ""
                        minWidth = 0
                        minimumWidth = 0
                        insetTop = 0
                        insetBottom = 0
                        cornerRadius = dp(24)
                        contentDescription = "发送"
                        setOnClickListener { performSend() }
                    },
                )
            }
        }

    @HikagableAnnotation
    private fun Hikage.Performer<LinearLayout.LayoutParams>.buildAttachmentPanel(): LinearLayout {
        val panel = HLinearLayout(
            lparams = LayoutParams(widthMatchParent = true),
            init = {
                orientation = LinearLayout.HORIZONTAL
                visibility = View.GONE
                setBackgroundColor(
                    MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorSurfaceContainer,
                    ),
                )
                setPadding(dp(20), dp(16), dp(20), dp(16))
                EdgeToEdgeInsets.applyHorizontalInsets(this)
            },
        ) {
            HLinearLayout(
                lparams = LayoutParams(
                    width = ViewGroup.LayoutParams.WRAP_CONTENT,
                    height = ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
                init = {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                },
            ) {
                HMaterialButton(
                    lparams = LayoutParams(width = dp(56), height = dp(56)) {
                        gravity = Gravity.CENTER_HORIZONTAL
                    },
                    init = {
                        icon = ContextCompat.getDrawable(context, R.drawable.ic_image)
                        iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                        iconPadding = 0
                        text = ""
                        minWidth = 0
                        minimumWidth = 0
                        insetTop = 0
                        insetBottom = 0
                        cornerRadius = dp(28)
                        contentDescription = "相册"
                        backgroundTintList = ColorStateList.valueOf(
                            MaterialColors.getColor(
                                this,
                                com.google.android.material.R.attr.colorSurfaceContainerHigh,
                            ),
                        )
                        iconTint = ColorStateList.valueOf(
                            MaterialColors.getColor(
                                this,
                                com.google.android.material.R.attr.colorOnSurface,
                            ),
                        )
                        setOnClickListener {
                            hideAttachmentPanel()
                            onPickImage()
                        }
                    },
                )
                HMaterialTextView(
                    lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT) {
                        topMargin = dp(6)
                    },
                    init = {
                        text = "相册"
                        TextViewCompat.setTextAppearance(
                            this,
                            com.google.android.material.R.style.TextAppearance_Material3_LabelMedium,
                        )
                        setTextColor(
                            MaterialColors.getColor(
                                this,
                                com.google.android.material.R.attr.colorOnSurfaceVariant,
                            ),
                        )
                    },
                )
            }
        }
        attachmentPanel = panel
        return panel
    }

    private fun toggleAttachmentPanel() {
        if (panelOpen) hideAttachmentPanel() else showAttachmentPanel()
    }

    private fun showAttachmentPanel() {
        if (panelOpen) return
        panelOpen = true
        input.clearFocus()
        context.getSystemService(InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(input.windowToken, 0)
        animatePanel(show = true)
    }

    private fun hideAttachmentPanel() {
        if (!panelOpen) return
        panelOpen = false
        animatePanel(show = false)
    }

    private fun animatePanel(show: Boolean) {
        val panel = attachmentPanel
        panelAnimator?.cancel()

        val parentWidth = (panel.parent as? View)?.width?.takeIf { it > 0 }
            ?: context.resources.displayMetrics.widthPixels
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(parentWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val fullHeight = panel.measuredHeight.coerceAtLeast(1)
        val startHeight = if (show) 0 else panel.height.takeIf { it > 0 } ?: fullHeight
        val endHeight = if (show) fullHeight else 0
        if (show) panel.visibility = View.VISIBLE

        var canceled = false
        panelAnimator = ValueAnimator.ofInt(startHeight, endHeight).apply {
            duration = if (show) 220L else 180L
            interpolator = if (show) DecelerateInterpolator(1.4f) else AccelerateInterpolator(1.4f)
            addUpdateListener { animation ->
                val params = panel.layoutParams
                params.height = animation.animatedValue as Int
                panel.layoutParams = params
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: Animator) {
                    canceled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (canceled) return
                    val params = panel.layoutParams
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    panel.layoutParams = params
                    if (!show) panel.visibility = View.GONE
                }
            })
            start()
        }
    }

    fun bind(owner: LifecycleOwner, viewModel: ChatDetailViewModel, accountUin: String) {
        if (bound) return
        bound = true
        boundViewModel = viewModel
        messageActionHandler = { message -> showMessageActions(viewModel, message) }
        AvatarLoader.bindLogo(
            toolbar = toolbar,
            localPath = target.avatarPath,
            urls = AvatarSources.forChatTarget(target),
            fallback = ContextCompat.getDrawable(context, R.drawable.ic_account_circle),
        )
        statusCard.setOnClickListener { viewModel.retry() }
        statusCard.contentDescription = "聊天状态，点按重试"
        swipeRefresh.setOnRefreshListener { viewModel.loadOlder() }
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
                            updateEmptyState()
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
                        lastLoading = loading
                        statusProgress.visibility = if (loading) View.VISIBLE else View.GONE
                        updateEmptyState()
                    }
                }
                launch {
                    viewModel.loadingOlder.collectLatest { loading ->
                        swipeRefresh.isRefreshing = loading
                    }
                }
                launch {
                    viewModel.hasOlder.collectLatest { hasOlder ->
                        swipeRefresh.isEnabled = hasOlder
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

    private fun updateEmptyState() {
        emptyView.visibility = if (adapter.currentList.isEmpty() && !lastLoading) {
            View.VISIBLE
        } else {
            View.GONE
        }
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
            if (message.outgoing && message.messageId > 0L &&
                message.sendStatus != 0 && message.sendStatus != 2
            ) {
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

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
