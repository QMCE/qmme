package rj.qmme.ui

import android.content.Context
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.Menu
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
import com.highcapable.hikage.widget.com.google.android.material.button.MaterialButton
import com.highcapable.hikage.widget.com.google.android.material.card.MaterialCardView
import com.highcapable.hikage.widget.com.google.android.material.imageview.ShapeableImageView
import com.highcapable.hikage.widget.com.google.android.material.progressindicator.CircularProgressIndicator
import com.highcapable.hikage.widget.com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import rj.qmme.R
import rj.qmme.data.AppSettings
import rj.qmme.data.chat.DraftStore
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
    private val onOpenSettings: () -> Unit,
    private val onOpenGroupManagement: (() -> Unit)?,
    private val onOpenSearch: () -> Unit,
    private val onOpenSummary: () -> Unit,
    private val onOpenVoiceRecord: () -> Unit,
    private val onForwardMessage: (ChatDetailViewModel.UiMessage) -> Unit,
    private val onBatchForward: () -> Unit,
) : HikageScreen {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var statusCard: MaterialCardView
    private lateinit var statusProgress: CircularProgressIndicator
    private lateinit var statusText: MaterialTextView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var jumpToLatest: MaterialButton
    private lateinit var replyBar: MaterialCardView
    private lateinit var replySummary: MaterialTextView
    private lateinit var multiSelectBar: LinearLayout
    private lateinit var multiSelectCount: MaterialTextView
    private lateinit var composerCard: MaterialCardView
    private lateinit var input: TextInputEditText
    private lateinit var imageButton: MaterialButton
    private lateinit var sendButton: MaterialButton
    private lateinit var attachmentPanel: View
    private var panelOpen = false
    private var panelAnimator: SpringAnimation? = null
    private var messageActionHandler: ((ChatDetailViewModel.UiMessage) -> Unit)? = null
    private var draftWatcher: TextWatcher? = null
    private val adapter = MessageAdapter(
        isGroup = target.chatType == 2,
        onImageClick = onOpenImage,
        onMessageLongClick = { message -> messageActionHandler?.invoke(message) },
        onVoiceClick = { voice -> boundViewModel?.toggleVoicePlayback(voice) },
        onMessageClick = { message ->
            val vm = boundViewModel ?: return@MessageAdapter
            if (vm.multiSelectMode.value) {
                vm.toggleSelection(message.messageId)
            }
        },
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
                buildReplyBar()
                buildMultiSelectBar()
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
                menu.clear()
                menu.add(Menu.NONE, MENU_SEARCH, 0, "搜索").apply {
                    setIcon(R.drawable.ic_search)
                    setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
                }
                menu.add(Menu.NONE, MENU_SUMMARY, 1, "AI 摘要").apply {
                    setIcon(R.drawable.ic_info)
                    setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
                }
                menu.add(Menu.NONE, MENU_MORE, 2, "更多").apply {
                    setIcon(R.drawable.ic_settings)
                    setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_IF_ROOM)
                }
                setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        MENU_SEARCH -> {
                            onOpenSearch()
                            true
                        }
                        MENU_SUMMARY -> {
                            onOpenSummary()
                            true
                        }
                        MENU_MORE -> {
                            if (target.chatType == 2 && onOpenGroupManagement != null) {
                                MessageActionSheet.show(
                                    context,
                                    listOf(
                                        MessageActionSheet.Action("群管理", R.drawable.ic_group) {
                                            onOpenGroupManagement.invoke()
                                        },
                                        MessageActionSheet.Action("会话设置", R.drawable.ic_settings) {
                                            onOpenSettings()
                                        },
                                    ),
                                )
                            } else {
                                onOpenSettings()
                            }
                            true
                        }
                        else -> false
                    }
                }
                EdgeToEdgeInsets.applyHorizontalInsets(this)
            },
        )
        return toolbar
    }

    @Hikagable
    private fun Hikage.Performer<LinearLayout.LayoutParams>.buildReplyBar(): MaterialCardView {
        replyBar = MaterialCardView(
            lparams = LayoutParams(widthMatchParent = true) {
                marginStart = dp(12)
                marginEnd = dp(12)
                topMargin = dp(4)
            },
            init = {
                visibility = View.GONE
                radius = dp(16).toFloat()
                strokeWidth = 0
                cardElevation = 0f
                setCardBackgroundColor(
                    MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorSecondaryContainer,
                    ),
                )
                EdgeToEdgeInsets.applyHorizontalInsets(this)
            },
        ) {
            LinearLayout(
                lparams = LayoutParams(widthMatchParent = true),
                init = {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(8), dp(4), dp(8))
                },
            ) {
                replySummary = MaterialTextView(
                    lparams = LayoutParams(width = 0, height = ViewGroup.LayoutParams.WRAP_CONTENT) {
                        weight = 1f
                    },
                    init = {
                        TextViewCompat.setTextAppearance(
                            this,
                            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                        )
                        maxLines = 2
                        textColor = MaterialColors.getColor(
                            this,
                            com.google.android.material.R.attr.colorOnSecondaryContainer,
                        )
                    },
                )
                IconButton(
                    lparams = LayoutParams(width = dp(40), height = dp(40)),
                    init = {
                        icon = drawableResource(R.drawable.ic_close)
                        contentDescription = "取消回复"
                        setOnClickListener { boundViewModel?.clearPendingReply() }
                    },
                )
            }
        }
        return replyBar
    }

    @Hikagable
    private fun Hikage.Performer<LinearLayout.LayoutParams>.buildMultiSelectBar(): LinearLayout {
        multiSelectBar = LinearLayout(
            lparams = LayoutParams(widthMatchParent = true),
            init = {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = View.GONE
                setBackgroundColor(
                    MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorSurfaceContainerHigh,
                    ),
                )
                setPadding(dp(12), dp(8), dp(12), dp(8))
                EdgeToEdgeInsets.applyHorizontalInsets(this)
            },
        ) {
            multiSelectCount = MaterialTextView(
                lparams = LayoutParams(width = 0, height = ViewGroup.LayoutParams.WRAP_CONTENT) {
                    weight = 1f
                },
                init = {
                    text = "已选 0 条"
                    TextViewCompat.setTextAppearance(
                        this,
                        com.google.android.material.R.style.TextAppearance_Material3_TitleSmall,
                    )
                },
            )
            MaterialButton(
                lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT) {
                    marginEnd = dp(4)
                },
                init = {
                    text = "转发"
                    setOnClickListener { onBatchForward() }
                },
            )
            MaterialButton(
                lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT) {
                    marginEnd = dp(4)
                },
                init = {
                    text = "删除"
                    setOnClickListener {
                        val vm = boundViewModel ?: return@setOnClickListener
                        val count = vm.selectedMsgIds.value.size
                        MaterialAlertDialogBuilder(context)
                            .setTitle("删除选中的 $count 条消息？")
                            .setMessage("只会从当前聊天记录中删除。")
                            .setNegativeButton("取消", null)
                            .setPositiveButton("删除") { _, _ -> vm.batchDeleteSelected() }
                            .show()
                    }
                },
            )
            MaterialButton(
                lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT),
                init = {
                    text = "取消"
                    setOnClickListener { boundViewModel?.exitMultiSelect() }
                },
            )
        }
        return multiSelectBar
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
                        addOnScrollListener(object : RecyclerView.OnScrollListener() {
                            override fun onScrolled(view: RecyclerView, dx: Int, dy: Int) {
                                updateJumpToLatest()
                            }
                        })
                    },
                )
            }
            // Anchored over the list, not in the composer: reading history is a
            // list state, so the affordance to leave it lives on the list.
            jumpToLatest = TonalIconButton(
                lparams = LayoutParams(width = dp(44), height = dp(44)) {
                    gravity = Gravity.BOTTOM or Gravity.END
                    marginEnd = dp(16)
                    bottomMargin = dp(16)
                },
                init = {
                    icon = drawableResource(R.drawable.ic_arrow_downward)
                    contentDescription = "回到最新消息"
                    visibility = View.GONE
                    setOnClickListener { scrollToLatest() }
                },
            )
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
    private fun Hikage.Performer<LinearLayout.LayoutParams>.buildComposer(): MaterialCardView {
        composerCard = MaterialCardView(
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
                            if (actionId == EditorInfo.IME_ACTION_SEND ||
                                (AppSettings.enterToSend(context) &&
                                    actionId == EditorInfo.IME_ACTION_DONE)
                            ) {
                                performSend()
                                true
                            } else {
                                false
                            }
                        }
                        setOnClickListener { hideAttachmentPanel() }
                        if (AppSettings.enterToSend(context)) {
                            imeOptions = EditorInfo.IME_ACTION_SEND
                            isSingleLine = true
                            maxLines = 1
                        }
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
        return composerCard
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
            LinearLayout(
                lparams = LayoutParams(
                    width = ViewGroup.LayoutParams.WRAP_CONTENT,
                    height = ViewGroup.LayoutParams.WRAP_CONTENT,
                ) {
                    marginStart = dp(20)
                },
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
                        icon = drawableResource(R.drawable.ic_mic)
                        contentDescription = "语音"
                        setOnClickListener {
                            hideAttachmentPanel()
                            onOpenVoiceRecord()
                        }
                    },
                )
                MaterialTextView(
                    lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT) {
                        topMargin = dp(6)
                    },
                    init = {
                        text = "语音"
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

        val draft = DraftStore.load(context, target.peerUid, target.chatType)
        if (draft.isNotBlank() && input.text.isNullOrEmpty()) {
            input.setText(draft)
            input.setSelection(draft.length)
        }
        draftWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                DraftStore.save(
                    context,
                    target.peerUid,
                    target.chatType,
                    s?.toString().orEmpty(),
                )
            }
        }.also(input::addTextChangedListener)

        owner.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.messages.collectLatest { messages ->
                        val previous = adapter.currentList
                        val tailChanged =
                            previous.lastOrNull()?.stableId != messages.lastOrNull()?.stableId
                        val wasNearBottom = isNearBottom()
                        val shouldFollowBottom = previous.isEmpty() ||
                                (tailChanged &&
                                        (wasNearBottom || messages.lastOrNull()?.outgoing == true))
                        adapter.submitList(messages) {
                            if (shouldFollowBottom && messages.isNotEmpty()) {
                                recyclerView.scrollToPosition(messages.lastIndex)
                            }
                            updateEmptyState()
                            updateJumpToLatest()
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
                    }
                }
                launch {
                    viewModel.pendingReply.collectLatest { reply ->
                        if (reply == null) {
                            Motion.fadeVisibility(replyBar, visible = false)
                        } else {
                            replySummary.text = "回复 ${reply.senderName}：${reply.summary}"
                            Motion.fadeVisibility(replyBar, visible = true)
                            input.requestFocus()
                            context.getSystemService(InputMethodManager::class.java)
                                ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
                        }
                    }
                }
                launch {
                    viewModel.multiSelectMode.collectLatest { enabled ->
                        renderMultiSelect(enabled, viewModel.selectedMsgIds.value)
                    }
                }
                launch {
                    viewModel.selectedMsgIds.collectLatest { ids ->
                        renderMultiSelect(viewModel.multiSelectMode.value, ids)
                    }
                }
            }
        }
        viewModel.openChat(target, accountUin)
    }

    private fun renderMultiSelect(enabled: Boolean, ids: Set<Long>) {
        adapter.updateSelection(enabled, ids)
        Motion.fadeVisibility(multiSelectBar, visible = enabled)
        Motion.fadeVisibility(composerCard, visible = !enabled)
        if (enabled) {
            hideAttachmentPanel()
            Motion.fadeVisibility(replyBar, visible = false)
        }
        multiSelectCount.text = "已选 ${ids.size} 条"
        if (::toolbar.isInitialized) {
            toolbar.title = if (enabled) "多选" else target.title
            toolbar.menu.findItem(MENU_SEARCH)?.isVisible = !enabled
            toolbar.menu.findItem(MENU_SUMMARY)?.isVisible = !enabled
            toolbar.menu.findItem(MENU_MORE)?.isVisible = !enabled
            if (enabled) {
                toolbar.setNavigationOnClickListener { boundViewModel?.exitMultiSelect() }
            } else {
                toolbar.setNavigationOnClickListener { onBack() }
            }
        }
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

    /**
     * "Near" spans the last two rows, not strictly the last pixel: a list that
     * has drifted a few dp — an IME resize, a settled fling — should still
     * count as following the conversation.
     */
    private fun isNearBottom(): Boolean {
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return true
        val last = layoutManager.findLastVisibleItemPosition()
        if (last == RecyclerView.NO_POSITION) return true
        return last >= adapter.itemCount - 2
    }

    private fun updateJumpToLatest() {
        Motion.fadeVisibility(
            jumpToLatest,
            visible = adapter.itemCount > 0 && !isNearBottom(),
        )
    }

    private fun scrollToLatest() {
        val lastIndex = adapter.itemCount - 1
        if (lastIndex < 0) return
        val layoutManager = recyclerView.layoutManager as? LinearLayoutManager
        val firstVisible = layoutManager?.findFirstVisibleItemPosition() ?: 0
        // A smooth scroll across a long backlog animates for seconds; jump
        // most of the way first so the glide is always short.
        if (lastIndex - firstVisible > SMOOTH_SCROLL_SPAN) {
            recyclerView.scrollToPosition(lastIndex - SMOOTH_SCROLL_SPAN)
        }
        recyclerView.smoothScrollToPosition(lastIndex)
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
        if (viewModel.multiSelectMode.value) {
            viewModel.toggleSelection(message.messageId)
            return
        }
        val actions = buildList {
            if (message.messageId > 0L) {
                add(
                    MessageActionSheet.Action("回复", R.drawable.ic_reply) {
                        if (!viewModel.prepareReply(message)) {
                            context.toast(viewModel.statusText.value.ifBlank { "无法回复" })
                        }
                    },
                )
                add(
                    MessageActionSheet.Action("转发", R.drawable.ic_forward) {
                        onForwardMessage(message)
                    },
                )
                add(
                    MessageActionSheet.Action("多选", R.drawable.ic_check) {
                        viewModel.enterMultiSelect(message.messageId)
                    },
                )
            }
            if (message.text.isNotBlank() && message.voice == null) {
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
        MessageActionSheet.show(context, actions)
    }

    private fun performSend() {
        val viewModel = boundViewModel ?: return
        val value = input.textToString()
        if (viewModel.sendText(value)) {
            input.text?.clear()
            DraftStore.clear(context, target.peerUid, target.chatType)
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val SMOOTH_SCROLL_SPAN = 20
        const val MENU_SEARCH = 1001
        const val MENU_MORE = 1002
        const val MENU_SUMMARY = 1003
    }
}
