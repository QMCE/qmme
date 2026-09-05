package rj.qmme.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import com.highcapable.betterandroid.ui.extension.component.base.getDrawableCompat
import com.highcapable.betterandroid.ui.extension.component.launch
import com.highcapable.betterandroid.ui.extension.view.textToString
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.hikage.annotation.Hikagable
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.FrameLayout
import com.highcapable.hikage.widget.android.widget.LinearLayout
import com.highcapable.hikage.widget.androidx.recyclerview.widget.RecyclerView
import com.highcapable.hikage.widget.com.google.android.material.appbar.MaterialToolbar
import com.highcapable.hikage.widget.com.google.android.material.button.MaterialButton
import com.highcapable.hikage.widget.com.google.android.material.card.MaterialCardView
import com.highcapable.hikage.widget.com.google.android.material.progressindicator.CircularProgressIndicator
import com.highcapable.hikage.widget.com.google.android.material.textfield.TextInputEditText
import com.highcapable.hikage.widget.com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import rj.qmme.R
import rj.qmme.agent.AgentEngine
import rj.qmme.agent.AgentRunStatus
import rj.qmme.agent.AgentSession
import rj.qmme.agent.AgentSubsystem
import rj.qmme.agent.AgentUiMsg
import rj.qmme.agent.ApprovalController
import rj.qmme.agent.ApprovalRequest
import rj.qmme.ui.hikage.FilledIconButton

/**
 * Phone-side chat surface for the Agent (Fluoxetine). The watch version
 * renders through the pseudo contact in the chat list; on the phone it gets
 * a dedicated page: bubble list, run-status strip, inline approval cards for
 * write tools, and a composer that turns into a stop button while running.
 */
class AgentChatHikagable(
    private val context: Context,
    private val onBack: () -> Unit,
) : HikageScreen {

    private lateinit var statusCard: MaterialCardView
    private lateinit var statusProgress: CircularProgressIndicator
    private lateinit var statusText: MaterialTextView
    private lateinit var chatList: RecyclerView
    private lateinit var approvalHost: LinearLayout
    private lateinit var input: TextInputEditText
    private lateinit var sendButton: MaterialButton

    private val adapter = AgentChatAdapter()
    private var bound = false
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
                        title = AgentSession.PEER_NAME
                        subtitle = "AI 助手 · 可调用 QQ 工具"
                        navigationIcon = context.getDrawableCompat(R.drawable.ic_arrow_back)
                        setNavigationContentDescription("返回")
                        setNavigationOnClickListener { onBack() }
                        EdgeToEdgeInsets.applyHorizontalInsets(this)
                    },
                )
                buildStatusCard()
                RecyclerView(
                    lparams = LayoutParams(widthMatchParent = true, height = 0) { weight = 1f },
                    init = {
                        chatList = this
                        layoutManager = LinearLayoutManager(context).apply { stackFromEnd = true }
                        adapter = this@AgentChatHikagable.adapter
                        clipToPadding = false
                        overScrollMode = View.OVER_SCROLL_NEVER
                        setPadding(0, dp(8), 0, dp(8))
                    },
                )
                buildApprovalHost()
                buildComposer()
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true, height = 0),
                    init = { EdgeToEdgeInsets.applyBottomInsetSpacerWithIme(this) },
                )
            }
        }.also { cachedHikage = it }

    @Hikagable
    private fun Hikage.Performer<LinearLayout.LayoutParams>.buildStatusCard() {
        statusCard = MaterialCardView(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = dp(8)
                marginStart = dp(12)
                marginEnd = dp(12)
            },
            init = {
                visibility = View.GONE
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
                    setPadding(dp(16), dp(10), dp(16), dp(10))
                },
            ) {
                statusProgress = CircularProgressIndicator(
                    lparams = LayoutParams(width = dp(18), height = dp(18)) {
                        marginEnd = dp(10)
                    },
                    init = { isIndeterminate = true },
                )
                statusText = MaterialTextView(
                    lparams = LayoutParams(width = 0, height = ViewGroup.LayoutParams.WRAP_CONTENT) {
                        weight = 1f
                    },
                    init = {
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
    }

    @Hikagable
    private fun Hikage.Performer<LinearLayout.LayoutParams>.buildApprovalHost() {
        approvalHost = LinearLayout(
            lparams = LayoutParams(widthMatchParent = true),
            init = {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(4), dp(12), dp(4))
            },
        )
    }

    @Hikagable
    private fun Hikage.Performer<LinearLayout.LayoutParams>.buildComposer() {
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
                input = TextInputEditText(
                    lparams = LayoutParams(
                        width = 0,
                        height = ViewGroup.LayoutParams.WRAP_CONTENT,
                    ) {
                        weight = 1f
                        gravity = Gravity.CENTER_VERTICAL
                    },
                    init = {
                        hint = "让 AI 帮你做点什么"
                        background = null
                        textColor = MaterialColors.getColor(
                            this,
                            com.google.android.material.R.attr.colorOnSurface,
                        )
                        setHintTextColor(
                            MaterialColors.getColor(
                                this,
                                com.google.android.material.R.attr.colorOnSurfaceVariant,
                            ),
                        )
                        maxLines = 4
                        minLines = 1
                        imeOptions = EditorInfo.IME_ACTION_SEND
                        setPadding(dp(6), dp(12), dp(6), dp(12))
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
                sendButton = FilledIconButton(
                    lparams = LayoutParams(width = dp(48), height = dp(48)) {
                        marginStart = dp(4)
                        gravity = Gravity.BOTTOM
                    },
                    init = {
                        icon = context.getDrawableCompat(R.drawable.ic_send)
                        contentDescription = "发送"
                        setOnClickListener { performSend() }
                    },
                )
            }
        }
    }

    fun bind(owner: LifecycleOwner) {
        if (bound) return
        bound = true
        AgentSubsystem.ensure(context)
        AgentSession.setInChat(true)

        owner.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    AgentSession.uiMessages.collectLatest { messages ->
                        adapter.submitList(messages) {
                            if (messages.isNotEmpty()) chatList.scrollToPosition(messages.lastIndex)
                        }
                    }
                }
                launch {
                    AgentSession.runStatus.collectLatest { status ->
                        renderStatus(status)
                    }
                }
                launch {
                    ApprovalController.pending.collectLatest { pending ->
                        renderApprovals(pending)
                    }
                }
            }
        }
    }

    /** Called when the page leaves the navigation stack. */
    fun dispose() {
        AgentSession.setInChat(false)
    }

    private fun renderStatus(status: AgentRunStatus) {
        if (::statusCard.isInitialized.not()) return
        when (status) {
            AgentRunStatus.Idle -> {
                statusCard.visibility = View.GONE
                sendButton.icon = context.getDrawableCompat(R.drawable.ic_send)
                sendButton.contentDescription = "发送"
            }
            AgentRunStatus.Running -> {
                statusText.text = "正在思考…"
                statusCard.visibility = View.VISIBLE
                statusProgress.visibility = View.VISIBLE
                sendButton.icon = context.getDrawableCompat(R.drawable.ic_stop)
                sendButton.contentDescription = "停止"
            }
            AgentRunStatus.WaitingApproval -> {
                statusText.text = "等待你批准 AI 的操作…"
                statusCard.visibility = View.VISIBLE
                statusProgress.visibility = View.INVISIBLE
                sendButton.icon = context.getDrawableCompat(R.drawable.ic_send)
                sendButton.contentDescription = "发送"
            }
        }
    }

    /** One card per queued write-tool approval; rebuilt on each emission. */
    private fun renderApprovals(pending: List<ApprovalRequest>) {
        if (::approvalHost.isInitialized.not()) return
        approvalHost.removeAllViews()
        if (pending.isEmpty()) {
            approvalHost.visibility = View.GONE
            return
        }
        approvalHost.visibility = View.VISIBLE
        pending.forEach { request -> approvalHost.addView(approvalCard(request)) }
    }

    private fun approvalCard(request: ApprovalRequest): View {
        val card = MaterialCardView(context)
        card.radius = dp(16).toFloat()
        card.cardElevation = 0f
        card.strokeWidth = 0
        card.setCardBackgroundColor(
            MaterialColors.getColor(
                card,
                com.google.android.material.R.attr.colorSecondaryContainer,
            ),
        )
        card.setContentPadding(dp(14), dp(10), dp(14), dp(6))
        card.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(4) }

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        column.addView(
            MaterialTextView(context).apply {
                text = "AI 想执行：${request.toolName}"
                TextViewCompat.setTextAppearance(
                    this,
                    com.google.android.material.R.style.TextAppearance_Material3_TitleSmall,
                )
                setTextColor(
                    MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorOnSecondaryContainer,
                    ),
                )
            },
        )
        column.addView(
            MaterialTextView(context).apply {
                text = request.summary
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
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(2) }
            },
        )
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        fun action(label: String, tint: Int, block: () -> Unit): MaterialButton {
            val button = MaterialButton(context)
            button.text = label
            TextViewCompat.setTextAppearance(
                button,
                com.google.android.material.R.style.TextAppearance_Material3_LabelLarge,
            )
            button.insetTop = 0
            button.insetBottom = 0
            button.minimumWidth = 0
            button.minWidth = dp(56)
            button.setBackgroundColor(Color.TRANSPARENT)
            button.setTextColor(
                MaterialColors.getColor(button, tint),
            )
            button.setOnClickListener { block() }
            button.layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(8) }
            return button
        }
        actions.addView(
            action("拒绝", androidx.appcompat.R.attr.colorError) {
                ApprovalController.decide(request.id, allow = false)
            },
        )
        actions.addView(
            action("允许", androidx.appcompat.R.attr.colorPrimary) {
                ApprovalController.decide(request.id, allow = true)
            },
        )
        column.addView(actions)
        card.addView(column)
        return card
    }

    private fun performSend() {
        val text = input.textToString().trim()
        if (text.isEmpty()) return
        if (AgentSession.runStatus.value != AgentRunStatus.Idle) {
            // Composer turns into a stop button while a run is in flight.
            AgentEngine.cancel()
            return
        }
        input.setText("")
        AgentSubsystem.sendUserMessage(text)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

/** Bubble list for [AgentSession.uiMessages]; diffs by stableKey. */
private class AgentChatAdapter :
    ListAdapter<AgentUiMsg, RecyclerView.ViewHolder>(DIFF) {

    companion object {
        private const val TYPE_SELF = 1
        private const val TYPE_ASSISTANT = 2
        private const val TYPE_SYSTEM = 3

        private val DIFF = object : DiffUtil.ItemCallback<AgentUiMsg>() {
            override fun areItemsTheSame(oldItem: AgentUiMsg, newItem: AgentUiMsg) =
                oldItem.stableKey == newItem.stableKey

            override fun areContentsTheSame(oldItem: AgentUiMsg, newItem: AgentUiMsg) =
                oldItem == newItem
        }
    }

    private lateinit var context: Context

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        context = recyclerView.context
    }

    override fun getItemViewType(position: Int): Int {
        val item = getItem(position)
        return when {
            item.isSystem -> TYPE_SYSTEM
            item.isSelf -> TYPE_SELF
            else -> TYPE_ASSISTANT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val root = FrameLayout(parent.context)
        root.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        val row = LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(parent.context, 12), dp(parent.context, 3), dp(parent.context, 12), dp(parent.context, 3))
        }
        root.addView(row)
        val bubble = MaterialTextView(parent.context).apply {
            TextViewCompat.setTextAppearance(
                this,
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
            )
            setTextIsSelectable(true)
            maxWidth = (parent.resources.displayMetrics.widthPixels * 78) / 100
        }
        when (viewType) {
            TYPE_SELF -> {
                row.gravity = Gravity.END
                bubble.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                bubble.background = rounded(
                    parent.context,
                    MaterialColors.getColor(bubble, androidx.appcompat.R.attr.colorPrimary),
                )
                bubble.setTextColor(
                    MaterialColors.getColor(bubble, com.google.android.material.R.attr.colorOnPrimary),
                )
            }
            TYPE_ASSISTANT -> {
                row.gravity = Gravity.START
                bubble.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                bubble.background = rounded(
                    parent.context,
                    MaterialColors.getColor(
                        bubble,
                        com.google.android.material.R.attr.colorSurfaceContainerHigh,
                    ),
                )
                bubble.setTextColor(
                    MaterialColors.getColor(bubble, com.google.android.material.R.attr.colorOnSurface),
                )
            }
            else -> {
                // System notes (errors / cancellations): centered, muted, borderless.
                row.gravity = Gravity.CENTER_HORIZONTAL
                bubble.gravity = Gravity.CENTER
                bubble.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                TextViewCompat.setTextAppearance(
                    bubble,
                    com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
                )
                bubble.setTextColor(
                    MaterialColors.getColor(
                        bubble,
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                    ),
                )
            }
        }
        row.addView(bubble)
        return ViewHolder(root, bubble)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        (holder as ViewHolder).bubble.text = item.text.ifBlank { "…" }
    }

    private fun rounded(context: Context, color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(context, 14).toFloat()
            setColor(color)
        }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private class ViewHolder(root: View, val bubble: MaterialTextView) :
        RecyclerView.ViewHolder(root)
}
