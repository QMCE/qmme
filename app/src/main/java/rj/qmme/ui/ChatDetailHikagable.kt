package rj.qmme.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.google.android.material.textfield.TextInputEditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.widget.TextViewCompat
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DefaultItemAnimator
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
import com.highcapable.betterandroid.system.extension.component.clipboardManager
import com.highcapable.betterandroid.system.extension.component.copy
import com.highcapable.betterandroid.ui.extension.component.base.getDrawableCompat
import com.highcapable.betterandroid.ui.extension.component.launch
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.betterandroid.ui.extension.view.textToString
import com.highcapable.betterandroid.ui.extension.view.toast
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.com.google.android.material.textfield.TextInputEditText
import com.highcapable.hikage.widget.android.widget.FrameLayout
import com.highcapable.hikage.widget.android.widget.LinearLayout
import com.highcapable.hikage.widget.androidx.recyclerview.widget.RecyclerView
import com.highcapable.hikage.widget.androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.highcapable.hikage.widget.com.google.android.material.appbar.MaterialToolbar
import com.highcapable.hikage.widget.com.google.android.material.card.MaterialCardView
import com.highcapable.hikage.widget.com.google.android.material.imageview.ShapeableImageView
import com.highcapable.hikage.widget.com.google.android.material.progressindicator.CircularProgressIndicator
import com.highcapable.hikage.widget.com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import rj.qmme.R
import rj.qmme.ui.hikage.FilledIconButton
import rj.qmme.ui.hikage.IconButton
import rj.qmme.ui.hikage.TonalIconButton
import rj.qmme.viewmodel.ChatDetailViewModel
import com.highcapable.hikage.annotation.Hikagable

/** Phone-first Material 3 Expressive chat screen, composed with Hikage Views. */
class ChatDetailHikagable(
    private val context: Context,
    private val target: ChatDetailViewModel.ChatTarget,
    private val onBack: () -> Unit,
    private val onPickImage: () -> Unit,
    onOpenImage: (ChatDetailViewModel.UiImage, View?) -> Unit,
) : HikageScreen {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var statusCard: MaterialCardView
    private lateinit var statusProgress: CircularProgressIndicator
    private lateinit var statusText: MaterialTextView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var input: TextInputEditText
    private lateinit var imageButton: MaterialButton
    private lateinit var sendButton: MaterialButton
    private lateinit var attachmentPanel: View
    private var panelOpen = false
    private var panelAnimator: SpringAnimation? = null
    private var messageActionHandler: ((ChatDetailViewModel.UiMessage) -> Unit)? = null
    private val adapter = MessageAdapter(
        isGroup = target.chatType == 2,
        onImageClick = onOpenImage,
        onMessageLongClick = { message -> messageActionHandler?.invoke(message) },
    )
    private var bound = false
    private var lastLoading = false
    private var messagesRevealed = false
    private var boundViewModel: ChatDetailViewModel? = null
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
                buildToolbar()
                buildStatusCard()
                buildMessageArea()
                buildComposer()
                buildAttachmentPanel()
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true, height = 0),
                    init = { EdgeToEdgeInsets.applyBottomInsetSpacerWithIme(this) },
                )
            }
        }.also { cachedHikage = it }

    @Hikagable
    private fun Hikage.Performer<LinearLayout.LayoutParams>.buildToolbar(): MaterialToolbar {
        toolbar = MaterialToolbar(
            lparams = LayoutParams(widthMatchParent = true),
            init = {
                navigationIcon = drawableResource(R.drawable.ic_arrow_back)
                setNavigationContentDescription("返回")
                setNavigationOnClickListener { onBack() }
                setContentInsetStartWithNavigation(0)
                logo = drawableResource(R.drawable.ic_account_circle)
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

    @Hikagable
    private fun Hikage.Performer<LinearLayout.LayoutParams>.buildStatusCard(): MaterialCardView {
        statusCard = MaterialCardView(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = dp(12)
                marginStart = dp(12)
                marginEnd = dp(12)
                bottomMargin = dp(4)
            },
            init = {
                // Filled inline banner: tonal surface, no shadow. The default
                // elevated card reads as a floating actionable element, which
                // a passive status strip is not.
                cardElevation = 0f
                strokeWidth = 0
                setCardBackgroundColor(
                    MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorSurfaceContainerHigh,
                    ),
                )
            },
        ) {
            LinearLayout(
                lparams = LayoutParams(matchParent = true),
                init = {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                },
            ) {
                statusProgress = CircularProgressIndicator(
                    lparams = LayoutParams(width = dp(20), height = dp(20)) {
                        marginEnd = dp(12)
                    },
                    init = {
                        isIndeterminate = true
                        visibility = View.GONE
                    },
                )
                statusText = MaterialTextView(
                    lparams = LayoutParams(
                        width = 0,
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                    ) {
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

    @Hikagable
    private fun Hikage.Performer<LinearLayout.LayoutParams>.buildMessageArea(): FrameLayout =
        FrameLayout(
            lparams = LayoutParams(widthMatchParent = true, height = 0) { weight = 1f },
        ) {
            swipeRefresh = SwipeRefreshLayout(
                lparams = LayoutParams(matchParent = true),
                init = {
                    applyM3Colors(this)
                    // The history arrives after the screen is already on top of
                    // the stack, so the list starts transparent and is faded in
                    // by revealMessages() instead of popping into place.
                    alpha = 0f
                },
            ) {
                recyclerView = RecyclerView(
                    lparams = LayoutParams(matchParent = true),
                    init = {
                        layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
                        adapter = this@ChatDetailHikagable.adapter
                        // Keep add/remove animations (new bubbles slide-fade in)
                        // but disable change animations: grouped-corner rebinds
                        // use notifyItemChanged and would cross-fade the whole
                        // row on every send otherwise.
                        itemAnimator = DefaultItemAnimator().apply {
                            supportsChangeAnimations = false
                            addDuration = 220L
                            removeDuration = 160L
                        }
                        clipToPadding = false
                        overScrollMode = View.OVER_SCROLL_NEVER
                        setPadding(0, dp(8), 0, dp(12))
                    },
                )
            }
            emptyView = LinearLayout(
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
                // M3 Expressive empty state: glyph on a cookie-12 badge in
                // secondaryContainer, not a lone faded icon.
                ShapeableImageView(
                    lparams = LayoutParams(width = dp(96), height = dp(96)),
                    init = {
                        setImageDrawable(
                            ExpressiveShapes.emptyStateBadge(
                                anchor = this,
                                glyph = context.getDrawableCompat(R.drawable.ic_chat),
                                sizePx = dp(96),
                            ),
                        )
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                )
                MaterialTextView(
                    lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT) {
                        topMargin = dp(16)
                    },
                    init = {
                        text = "还没有消息"
                        TextViewCompat.setTextAppearance(
                            this,
                            com.google.android.material.R.style.TextAppearance_Material3_TitleMedium_Emphasized,
                        )
                    },
                )
                MaterialTextView(
                    lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT) {
                        topMargin = dp(4)
                    },
                    init = {
                        text = "打个招呼吧"
                        TextViewCompat.setTextAppearance(
                            this,
                            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                        )
                        textColor = MaterialColors.getColor(
                            this,
                            com.google.android.material.R.attr.colorOnSurfaceVariant,
                        )
                    },
                )
            }
        }

    @Hikagable
    private fun Hikage.Performer<LinearLayout.LayoutParams>.buildComposer(): MaterialCardView =
        MaterialCardView(
            lparams = LayoutParams(widthMatchParent = true) {
                marginStart = dp(12)
                marginEnd = dp(12)
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
            LinearLayout(
                lparams = LayoutParams(widthMatchParent = true),
                init = {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.BOTTOM
                    setPadding(dp(6), dp(6), dp(6), dp(6))
                },
            ) {
                // Standard (transparent) icon button — the style already
                // tints to colorOnSurfaceVariant and morphs on press.
                imageButton = IconButton(
                    lparams = LayoutParams(width = dp(48), height = dp(48)) {
                        gravity = Gravity.BOTTOM
                    },
                    init = {
                        icon = drawableResource(R.drawable.ic_add)
                        contentDescription = "更多"
                        setOnClickListener { toggleAttachmentPanel() }
                    },
                )
                // TextInputEditText (used standalone) keeps M3 cursor, handle
                // and highlight theming that a bare framework EditText lacks.
                input = TextInputEditText(
                    lparams = LayoutParams(
                        width = 0,
                        height = ViewGroup.LayoutParams.WRAP_CONTENT
                    ) {
                        weight = 1f
                        gravity = Gravity.CENTER_VERTICAL
                    },
                    init = {
                        hint = "发点什么呢"
                        background = null
                        textColor = MaterialColors.getColor(
                            this,
                            com.google.android.material.R.attr.colorOnSurface
                        )
                        setHintTextColor(
                            MaterialColors.getColor(
                                this,
                                com.google.android.material.R.attr.colorOnSurfaceVariant,
                            ),
                        )
                        maxLines = 5
                        minLines = 1
                        imeOptions = EditorInfo.IME_ACTION_SEND
                        isSingleLine = false
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
                // Filled icon button: the M3 Expressive style supplies the
                // pressed shape morph and the 48dp target, so no manual
                // corner radius / inset squashing is needed.
                sendButton = FilledIconButton(
                    lparams = LayoutParams(width = dp(48), height = dp(48)) {
                        marginStart = dp(4)
                        gravity = Gravity.BOTTOM
                    },
                    init = {
                        icon = drawableResource(R.drawable.ic_send)
                        contentDescription = "发送"
                        setOnClickListener { performSend() }
                    },
                )
            }
        }

    @Hikagable
    private fun Hikage.Performer<LinearLayout.LayoutParams>.buildAttachmentPanel(): LinearLayout {
        val panel = LinearLayout(
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
            LinearLayout(
                lparams = LayoutParams(
                    width = ViewGroup.LayoutParams.WRAP_CONTENT,
                    height = ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
                init = {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_HORIZONTAL
                },
            ) {
                TonalIconButton(
                    lparams = LayoutParams(width = dp(56), height = dp(56)) {
                        gravity = Gravity.CENTER_HORIZONTAL
                    },
                    init = {
                        icon = drawableResource(R.drawable.ic_image)
                        contentDescription = "相册"
                        setOnClickListener {
                            hideAttachmentPanel()
                            onPickImage()
                        }
                    },
                )
                MaterialTextView(
                    lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT) {
                        topMargin = dp(6)
                    },
                    init = {
                        text = "相册"
                        TextViewCompat.setTextAppearance(
                            this,
                            com.google.android.material.R.style.TextAppearance_Material3_LabelMedium,
                        )
                        textColor = MaterialColors.getColor(
                            this,
                            com.google.android.material.R.attr.colorOnSurfaceVariant,
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

        // M3 Expressive motion is spring-based; the theme's fast spatial token
        // is the one meant for small container reveals like this panel.
        panelAnimator = Motion.animateHeight(
            view = panel,
            from = startHeight,
            to = endHeight,
            force = Motion.fastSpatial(context),
        ) {
            val params = panel.layoutParams
            params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            panel.layoutParams = params
            if (!show) panel.visibility = View.GONE
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
            fallback = context.getDrawableCompat(R.drawable.ic_account_circle),
        )
        statusCard.setOnClickListener { viewModel.retry() }
        statusCard.contentDescription = "聊天状态，点按重试"
        swipeRefresh.setOnRefreshListener { viewModel.loadOlder() }
        owner.launch {
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
                            // Reveal only once the list has actually been laid
                            // out at the bottom, so the fade shows the settled
                            // conversation rather than it scrolling into place.
                            if (messages.isNotEmpty()) revealMessages()
                        }
                    }
                }
                launch {
                    viewModel.statusText.collectLatest { text ->
                        statusText.text = text
                        Motion.fadeVisibility(statusCard, visible = text.isNotBlank())
                    }
                }
                launch {
                    viewModel.loading.collectLatest { loading ->
                        lastLoading = loading
                        Motion.fadeVisibility(statusProgress, loading)
                        updateEmptyState()
                        // An empty chat never delivers messages, so settling the
                        // load is the other signal that the screen is ready.
                        if (!loading) revealMessages()
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

    /**
     * The first delivered page and the load settling can land in the same
     * frame, so the flag keeps two spring animations off the same alpha.
     */
    private fun revealMessages() {
        if (messagesRevealed) return
        messagesRevealed = true
        Motion.fadeIn(swipeRefresh, Motion.defaultEffects(context))
    }

    private fun updateEmptyState() {
        Motion.fadeVisibility(
            emptyView,
            visible = adapter.currentList.isEmpty() && !lastLoading,
        )
    }

    private fun showMessageActions(
        viewModel: ChatDetailViewModel,
        message: ChatDetailViewModel.UiMessage,
    ) {
        val actions = buildList {
            if (message.text.isNotBlank()) {
                add(
                    MessageActionSheet.Action("复制", R.drawable.ic_copy) {
                        val clipboard = context.clipboardManager
                        clipboard.copy(message.text, "QQ 消息")
                        context.toast("已复制消息")
                    },
                )
            }
            if (message.outgoing && message.messageId > 0L) {
                add(
                    MessageActionSheet.Action("撤回", R.drawable.ic_undo, destructive = true) {
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
                    MessageActionSheet.Action("删除", R.drawable.ic_delete, destructive = true) {
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
                add(
                    MessageActionSheet.Action("重发", R.drawable.ic_refresh) {
                        viewModel.resendMessage(message)
                    },
                )
            }
        }
        // Segmented-list bottom sheet, not a centered dialog menu.
        MessageActionSheet.show(context, actions)
    }

    private fun performSend() {
        val viewModel = boundViewModel ?: return
        val value = input.textToString()
        if (viewModel.sendText(value)) input.text?.clear()
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
