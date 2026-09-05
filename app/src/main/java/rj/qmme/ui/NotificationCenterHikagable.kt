package rj.qmme.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.textview.MaterialTextView
import com.highcapable.betterandroid.ui.extension.component.base.getDrawableCompat
import com.highcapable.betterandroid.ui.extension.component.launch
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.hikage.annotation.Hikagable
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.LinearLayout
import com.highcapable.hikage.widget.androidx.core.widget.NestedScrollView
import com.highcapable.hikage.widget.com.google.android.material.appbar.MaterialToolbar
import com.highcapable.hikage.widget.com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import rj.qmme.R
import rj.qmme.data.notify.UiFriendRequest
import rj.qmme.data.notify.UiGroupNotice
import rj.qmme.viewmodel.FriendNotifyState
import rj.qmme.viewmodel.GroupNotifyState
import rj.qmme.viewmodel.NotificationCenterViewModel

/**
 * Phone-side notification center: friend requests + group system notices.
 *
 * Data flows from the shared kernel repositories (SharedNotifyRepositories);
 * this screen only renders and posts approve/decline operations. The static
 * skeleton is a Hikage tree; rows are plain M3 cards rebuilt on each state
 * emission (same pattern as MainHikagable's contact sections).
 */
class NotificationCenterHikagable(
    private val context: Context,
    private val onBack: () -> Unit,
) : HikageScreen {

    private lateinit var friendStatus: MaterialTextView
    private lateinit var friendSection: LinearLayout
    private lateinit var groupStatus: MaterialTextView
    private lateinit var groupSection: LinearLayout

    private var boundViewModel: NotificationCenterViewModel? = null
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
                        title = "新朋友与群通知"
                        setNavigationOnClickListener { onBack() }
                        navigationIcon = context.getDrawableCompat(R.drawable.ic_arrow_back)
                    },
                )
                NestedScrollView(
                    lparams = LayoutParams(widthMatchParent = true, height = 0) { weight = 1f },
                    init = {
                        isFillViewport = true
                        clipToPadding = false
                        setPadding(dp(12), dp(4), dp(12), dp(24))
                    },
                ) {
                    LinearLayout(
                        lparams = LayoutParams(widthMatchParent = true),
                        init = { orientation = LinearLayout.VERTICAL },
                    ) {
                        MaterialTextView(
                            lparams = LayoutParams(widthMatchParent = true) {
                                topMargin = dp(14)
                                bottomMargin = dp(2)
                                marginStart = dp(16)
                            },
                            init = {
                                text = "好友申请"
                                TextViewCompat.setTextAppearance(
                                    this,
                                    com.google.android.material.R.style.TextAppearance_Material3_LabelLarge_Emphasized,
                                )
                                textColor = MaterialColors.getColor(
                                    this,
                                    androidx.appcompat.R.attr.colorPrimary,
                                )
                            },
                        )
                        friendStatus = MaterialTextView(
                            lparams = LayoutParams(widthMatchParent = true) { topMargin = dp(4) },
                            init = {
                                text = "加载中…"
                                TextViewCompat.setTextAppearance(
                                    this,
                                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                                )
                                textColor = MaterialColors.getColor(
                                    this,
                                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                                )
                                setPadding(dp(16), dp(8), dp(16), dp(8))
                            },
                        )
                        friendSection = LinearLayout(
                            lparams = LayoutParams(widthMatchParent = true),
                            init = { orientation = LinearLayout.VERTICAL },
                        )
                        MaterialTextView(
                            lparams = LayoutParams(widthMatchParent = true) {
                                topMargin = dp(22)
                                bottomMargin = dp(2)
                                marginStart = dp(16)
                            },
                            init = {
                                text = "群系统通知"
                                TextViewCompat.setTextAppearance(
                                    this,
                                    com.google.android.material.R.style.TextAppearance_Material3_LabelLarge_Emphasized,
                                )
                                textColor = MaterialColors.getColor(
                                    this,
                                    androidx.appcompat.R.attr.colorPrimary,
                                )
                            },
                        )
                        groupStatus = MaterialTextView(
                            lparams = LayoutParams(widthMatchParent = true) { topMargin = dp(4) },
                            init = {
                                text = "加载中…"
                                TextViewCompat.setTextAppearance(
                                    this,
                                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                                )
                                textColor = MaterialColors.getColor(
                                    this,
                                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                                )
                                setPadding(dp(16), dp(8), dp(16), dp(8))
                            },
                        )
                        groupSection = LinearLayout(
                            lparams = LayoutParams(widthMatchParent = true),
                            init = { orientation = LinearLayout.VERTICAL },
                        )
                    }
                }
            }
        }.also { cachedHikage = it }

    fun bind(owner: LifecycleOwner, viewModel: NotificationCenterViewModel) {
        if (bound) return
        bound = true
        boundViewModel = viewModel
        viewModel.enterFriendRequests()
        viewModel.enterGroupNotices()

        owner.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.friendState.collectLatest { renderFriend(it) }
                }
                launch {
                    viewModel.groupState.collectLatest { renderGroup(it) }
                }
            }
        }
    }

    /** Called when the page leaves the navigation stack. */
    fun dispose() {
        boundViewModel?.let {
            it.leaveFriendRequests()
            it.leaveGroupNotices()
        }
        boundViewModel = null
    }

    private fun renderFriend(state: FriendNotifyState) {
        if (::friendStatus.isInitialized.not() || ::friendSection.isInitialized.not()) return
        val message = when {
            state.loading -> "加载中…"
            state.error != null -> state.error
            state.items.isEmpty() -> "暂无好友申请"
            else -> null
        }
        friendStatus.text = message
        friendStatus.visibility = if (message != null) View.VISIBLE else View.GONE
        friendSection.removeAllViews()
        state.items.forEach { item ->
            friendSection.addView(friendRow(item, acting = state.actingUid == item.uid))
        }
    }

    private fun renderGroup(state: GroupNotifyState) {
        if (::groupStatus.isInitialized.not() || ::groupSection.isInitialized.not()) return
        val message = when {
            state.loading -> "加载中…"
            state.error != null -> state.error
            state.items.isEmpty() -> "暂无群通知"
            else -> null
        }
        groupStatus.text = message
        groupStatus.visibility = if (message != null) View.VISIBLE else View.GONE
        groupSection.removeAllViews()
        state.items.forEach { item ->
            groupSection.addView(groupRow(item, acting = state.actingSeq == item.seq))
        }
    }

    private fun friendRow(item: UiFriendRequest, acting: Boolean): View {
        val content = rowCardContent()
        content.addView(avatarView(item.nick))
        content.addView(titleColumn(item.nick, item.message.ifBlank { "请求添加你为好友" }))
        if (item.pending) {
            content.addView(
                actionsView(
                    acting = acting,
                    onAccept = {
                        boundViewModel?.approveFriendRequest(item.uid, item.reqTime, accept = true)
                    },
                    onDecline = {
                        boundViewModel?.approveFriendRequest(item.uid, item.reqTime, accept = false)
                    },
                ),
            )
        } else {
            content.addView(statusView("已处理"))
        }
        return content.parent as View
    }

    private fun groupRow(item: UiGroupNotice, acting: Boolean): View {
        val content = rowCardContent()
        content.addView(avatarView(item.groupName))
        content.addView(titleColumn(item.title, item.subtitle))
        if (item.pending) {
            content.addView(
                actionsView(
                    acting = acting,
                    onAccept = { boundViewModel?.operateGroupNotice(item, accept = true) },
                    onDecline = { boundViewModel?.operateGroupNotice(item, accept = false) },
                ),
            )
        } else {
            content.addView(statusView(item.statusLabel))
        }
        return content.parent as View
    }

    /** Builds the wrapping card plus its inner content column and returns the column. */
    private fun rowCardContent(): LinearLayout {
        val card = MaterialCardView(context)
        card.radius = dp(16).toFloat()
        card.cardElevation = 0f
        card.strokeWidth = 0
        card.setCardBackgroundColor(
            MaterialColors.getColor(
                card,
                com.google.android.material.R.attr.colorSurfaceContainerLow,
            ),
        )
        card.setContentPadding(dp(12), dp(10), dp(12), dp(10))
        val cardParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        cardParams.topMargin = dp(6)
        card.layoutParams = cardParams
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        card.addView(content)
        return content
    }

    private fun avatarView(name: String): MaterialTextView {
        val view = MaterialTextView(context)
        val params = LinearLayout.LayoutParams(dp(40), dp(40))
        params.topMargin = dp(2)
        view.layoutParams = params
        view.gravity = Gravity.CENTER
        view.text = name.firstOrNull()?.toString() ?: "?"
        TextViewCompat.setTextAppearance(
            view,
            com.google.android.material.R.style.TextAppearance_Material3_TitleMedium,
        )
        view.textColor = MaterialColors.getColor(
            view,
            com.google.android.material.R.attr.colorOnPrimaryContainer,
        )
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(
            MaterialColors.getColor(
                view,
                com.google.android.material.R.attr.colorPrimaryContainer,
            ),
        )
        view.background = bg
        return view
    }

    private fun titleColumn(title: String, subtitle: String): LinearLayout {
        val column = LinearLayout(context)
        column.orientation = LinearLayout.VERTICAL
        val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT)
        params.weight = 1f
        params.marginStart = dp(12)
        column.layoutParams = params
        val titleView = MaterialTextView(context).apply {
            text = title
            TextViewCompat.setTextAppearance(
                this,
                com.google.android.material.R.style.TextAppearance_Material3_TitleSmall,
            )
            maxLines = 1
        }
        val subtitleView = MaterialTextView(context).apply {
            text = subtitle
            TextViewCompat.setTextAppearance(
                this,
                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
            )
            textColor = MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
            )
            maxLines = 2
        }
        column.addView(titleView)
        column.addView(subtitleView)
        return column
    }

    private fun actionsView(
        acting: Boolean,
        onAccept: () -> Unit,
        onDecline: () -> Unit,
    ): LinearLayout {
        val actions = LinearLayout(context)
        actions.orientation = LinearLayout.HORIZONTAL
        actions.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        params.topMargin = dp(4)
        actions.layoutParams = params

        fun button(label: String, action: () -> Unit): MaterialButton {
            val button = MaterialButton(context)
            button.text = label
            TextViewCompat.setTextAppearance(
                button,
                com.google.android.material.R.style.TextAppearance_Material3_LabelLarge,
            )
            button.setPadding(0, 0, 0, 0)
            button.insetTop = 0
            button.insetBottom = 0
            button.minimumWidth = 0
            button.minWidth = dp(56)
            button.isEnabled = !acting
            button.setOnClickListener { action() }
            val buttonParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            buttonParams.marginStart = dp(4)
            button.layoutParams = buttonParams
            return button
        }
        val decline = button("拒绝") { onDecline() }
        decline.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        decline.textColor = MaterialColors.getColor(
            decline,
            androidx.appcompat.R.attr.colorError,
        )
        val accept = button("同意") { onAccept() }
        accept.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        accept.textColor = MaterialColors.getColor(
            accept,
            androidx.appcompat.R.attr.colorPrimary,
        )
        actions.addView(decline)
        actions.addView(accept)
        return actions
    }

    private fun statusView(label: String): MaterialTextView {
        val view = MaterialTextView(context)
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        params.gravity = Gravity.CENTER_VERTICAL
        view.layoutParams = params
        view.text = label
        TextViewCompat.setTextAppearance(
            view,
            com.google.android.material.R.style.TextAppearance_Material3_LabelMedium,
        )
        view.textColor = MaterialColors.getColor(
            view,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
        )
        return view
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
