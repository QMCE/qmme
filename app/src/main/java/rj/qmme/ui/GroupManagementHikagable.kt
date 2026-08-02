package rj.qmme.ui

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textview.MaterialTextView
import com.highcapable.betterandroid.ui.extension.component.base.getDrawableCompat
import com.highcapable.hikage.annotation.Hikagable
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.androidx.core.widget.NestedScrollView
import com.highcapable.hikage.widget.androidx.recyclerview.widget.RecyclerView
import com.highcapable.hikage.widget.android.widget.LinearLayout
import com.highcapable.hikage.widget.com.google.android.material.appbar.MaterialToolbar
import com.highcapable.hikage.widget.com.google.android.material.button.MaterialButton
import com.highcapable.hikage.widget.com.google.android.material.card.MaterialCardView
import com.highcapable.hikage.widget.com.google.android.material.imageview.ShapeableImageView
import com.highcapable.hikage.widget.com.google.android.material.progressindicator.CircularProgressIndicator
import com.highcapable.hikage.widget.com.google.android.material.textview.MaterialTextView
import com.tencent.qqnt.kernelpublic.nativeinterface.MemberRole
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import rj.qmme.R
import rj.qmme.data.chat.GroupBulletinItem
import rj.qmme.data.chat.GroupMemberRepository
import rj.qmme.ui.hikage.IconButton
import rj.qmme.viewmodel.GroupManagementState
import rj.qmme.viewmodel.GroupManagementViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Phone group management screen (mute, bulletin, member kick). */
class GroupManagementHikagable(
    private val context: Context,
    private val onBack: () -> Unit,
) : HikageScreen {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var statusCard: MaterialCardView
    private lateinit var statusText: MaterialTextView
    private lateinit var statusProgress: CircularProgressIndicator
    private lateinit var headerTitle: MaterialTextView
    private lateinit var headerRole: MaterialTextView
    private lateinit var headerCode: MaterialTextView
    private lateinit var manageSection: LinearLayout
    private lateinit var allMutedSwitch: MaterialSwitch
    private lateinit var bulletinSection: LinearLayout
    private lateinit var bulletinList: LinearLayout
    private lateinit var publishBulletinButton: com.google.android.material.button.MaterialButton
    private lateinit var memberRecycler: RecyclerView
    private val memberAdapter = MemberAdapter()
    private var bound = false
    private var cachedHikage: Hikage.Delegate<*>? = null
    private var currentState: GroupManagementState = GroupManagementState()
    private var boundViewModel: GroupManagementViewModel? = null

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
                toolbar = MaterialToolbar(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        title = "群管理"
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
                        buildStatusCard()
                        manageSection = LinearLayout(
                            lparams = LayoutParams(widthMatchParent = true),
                            init = { orientation = LinearLayout.VERTICAL },
                        ) {
                            buildSectionLabel("管理")
                            buildAllMutedRow()
                        }
                        bulletinSection = LinearLayout(
                            lparams = LayoutParams(widthMatchParent = true),
                            init = { orientation = LinearLayout.VERTICAL },
                        ) {
                            buildSectionLabel("群公告")
                            bulletinList = LinearLayout(
                                lparams = LayoutParams(widthMatchParent = true),
                                init = { orientation = LinearLayout.VERTICAL },
                            )
                            publishBulletinButton = MaterialButton(
                                lparams = LayoutParams(widthMatchParent = true) {
                                    topMargin = dp(8)
                                },
                                init = {
                                    text = "发布公告"
                                    isAllCaps = false
                                    visibility = View.GONE
                                    setOnClickListener { showPublishBulletinDialog() }
                                },
                            )
                        }
                        buildSectionLabel("成员")
                        memberRecycler = RecyclerView(
                            lparams = LayoutParams(widthMatchParent = true),
                            init = {
                                layoutManager = LinearLayoutManager(context)
                                adapter = memberAdapter
                                isNestedScrollingEnabled = false
                                overScrollMode = View.OVER_SCROLL_NEVER
                            },
                        )
                    }
                }
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true, height = 0),
                    init = { EdgeToEdgeInsets.applyBottomInsetSpacer(this) },
                )
            }
        }.also { cachedHikage = it }

    fun bind(
        owner: LifecycleOwner,
        viewModel: GroupManagementViewModel,
        groupCode: Long,
        groupTitle: String,
    ) {
        if (bound) return
        bound = true
        boundViewModel = viewModel
        headerTitle.text = groupTitle.ifBlank { "群聊" }
        headerCode.text = "群号 $groupCode"
        viewModel.load(groupCode)

        owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collectLatest { state ->
                    currentState = state
                    render(state)
                }
            }
        }
    }

    private fun render(state: GroupManagementState) {
        headerRole.text = state.roleLabel
        toolbar.subtitle = state.roleLabel

        val statusMessage = buildStatusMessage(state)
        statusText.text = statusMessage
        Motion.fadeVisibility(statusCard, visible = statusMessage.isNotBlank())
        Motion.fadeVisibility(statusProgress, visible = state.loading || state.busy)

        manageSection.visibility = if (state.canManage) View.VISIBLE else View.GONE
        syncAllMutedSwitch(state.allMuted, state.busy)

        renderBulletins(state)
        publishBulletinButton.visibility = if (state.canManage) View.VISIBLE else View.GONE
        publishBulletinButton.isEnabled = !state.bulletinSaving

        memberAdapter.submit(
            members = state.members,
            canManage = state.canManage,
            actorRole = state.role,
            actionUid = state.memberActionUid,
        )
    }

    private fun buildStatusMessage(state: GroupManagementState): String {
        return state.error?.takeIf { it.isNotBlank() }
            ?: state.memberActionError?.takeIf { it.isNotBlank() }
            ?: state.bulletinError?.takeIf { it.isNotBlank() }
            ?: state.bulletinMessage?.takeIf { it.isNotBlank() }
            ?: state.membersError?.takeIf { it.isNotBlank() }
            ?: when {
                state.loading -> "正在加载群资料…"
                state.membersLoading -> "正在加载成员…"
                state.bulletinLoading -> "正在加载群公告…"
                else -> ""
            }
    }

    private fun syncAllMutedSwitch(checked: Boolean, busy: Boolean) {
        if (!::allMutedSwitch.isInitialized) return
        allMutedSwitch.setOnCheckedChangeListener(null)
        allMutedSwitch.isChecked = checked
        allMutedSwitch.isEnabled = !busy
        allMutedSwitch.setOnCheckedChangeListener(allMutedListener)
    }

    private val allMutedListener =
        android.widget.CompoundButton.OnCheckedChangeListener { _, enabled ->
            boundViewModel?.toggleAllMuted(enabled)
        }

    private fun renderBulletins(state: GroupManagementState) {
        bulletinList.removeAllViews()
        if (state.bulletinLoading && state.bulletins.isEmpty()) {
            bulletinList.addView(createBulletinPlaceholder("正在加载群公告…"))
            return
        }
        if (state.bulletins.isEmpty()) {
            bulletinList.addView(createBulletinPlaceholder("暂无群公告"))
            return
        }
        state.bulletins.forEach { item ->
            bulletinList.addView(createBulletinRow(item))
        }
    }

    private fun createBulletinPlaceholder(text: String): View {
        return MaterialTextView(context).apply {
            this.text = text
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
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
    }

    private fun createBulletinRow(item: GroupBulletinItem): View {
        val card = MaterialCardView(context).apply {
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(
                MaterialColors.getColor(
                    this,
                    com.google.android.material.R.attr.colorSurfaceContainerLow,
                ),
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (item.pinned) {
            titleRow.addView(
                MaterialTextView(context).apply {
                    text = "置顶"
                    TextViewCompat.setTextAppearance(
                        this,
                        com.google.android.material.R.style.TextAppearance_Material3_LabelSmall,
                    )
                    setTextColor(
                        MaterialColors.getColor(
                            this,
                            androidx.appcompat.R.attr.colorPrimary,
                        ),
                    )
                    setPadding(0, 0, dp(8), 0)
                },
            )
        }
        val timeText = formatBulletinTime(item.createTime)
        if (timeText.isNotBlank()) {
            titleRow.addView(
                MaterialTextView(context).apply {
                    text = timeText
                    TextViewCompat.setTextAppearance(
                        this,
                        com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
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
        content.addView(
            titleRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            MaterialTextView(context).apply {
                text = item.text.ifBlank { "（无文字内容）" }
                TextViewCompat.setTextAppearance(
                    this,
                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                )
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) },
        )
        card.addView(content)
        return card
    }

    private fun showPublishBulletinDialog() {
        val viewModel = boundViewModel ?: return
        val state = currentState
        if (!state.canManage) return

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        val input = EditText(context).apply {
            hint = "输入群公告内容"
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP
        }
        container.addView(
            input,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        val pinCheck = CheckBox(context).apply {
            text = "置顶公告"
        }
        container.addView(pinCheck)

        MaterialAlertDialogBuilder(context)
            .setTitle("发布群公告")
            .setView(container)
            .setPositiveButton("发布") { _, _ ->
                viewModel.publishBulletin(
                    text = input.text?.toString().orEmpty(),
                    pinned = pinCheck.isChecked,
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmKick(member: GroupMemberRepository.Member) {
        val viewModel = boundViewModel ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle("移出群聊")
            .setMessage("确定将 ${member.displayName} 移出群聊吗？")
            .setPositiveButton("移出") { _, _ -> viewModel.kickMember(member) }
            .setNegativeButton("取消", null)
            .show()
    }

    @Hikagable
    private fun Hikage.Performer<LinearLayout.LayoutParams>.buildHeader() {
        LinearLayout(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = dp(12)
            },
            init = {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
            },
        ) {
            headerTitle = MaterialTextView(
                lparams = LayoutParams(widthMatchParent = true),
                init = {
                    TextViewCompat.setTextAppearance(
                        this,
                        com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall_Emphasized,
                    )
                },
            )
            headerRole = MaterialTextView(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = dp(4)
                },
                init = {
                    TextViewCompat.setTextAppearance(
                        this,
                        com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                    )
                    setTextColor(
                        MaterialColors.getColor(
                            this,
                            androidx.appcompat.R.attr.colorPrimary,
                        ),
                    )
                },
            )
            headerCode = MaterialTextView(
                lparams = LayoutParams(widthMatchParent = true) {
                    topMargin = dp(2)
                },
                init = {
                    TextViewCompat.setTextAppearance(
                        this,
                        com.google.android.material.R.style.TextAppearance_Material3_BodySmall,
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

    @Hikagable
    private fun Hikage.Performer<LinearLayout.LayoutParams>.buildStatusCard() {
        statusCard = MaterialCardView(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = dp(8)
            },
            init = {
                cardElevation = 0f
                strokeWidth = 0
                setCardBackgroundColor(
                    MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorSurfaceContainerHigh,
                    ),
                )
                visibility = View.GONE
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
                        height = ViewGroup.LayoutParams.WRAP_CONTENT,
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
    }

    @Hikagable
    private fun Hikage.Performer<LinearLayout.LayoutParams>.buildAllMutedRow() {
        LinearLayout(
            lparams = LayoutParams(widthMatchParent = true) {
                bottomMargin = dp(8)
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
                        text = "全员禁言"
                        TextViewCompat.setTextAppearance(
                            this,
                            com.google.android.material.R.style.TextAppearance_Material3_BodyLarge,
                        )
                    },
                )
                textColumn.addView(
                    MaterialTextView(context).apply {
                        text = "开启后仅群主和管理员可发言"
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
                allMutedSwitch = MaterialSwitch(context).apply {
                    setOnCheckedChangeListener(allMutedListener)
                }
                addView(allMutedSwitch)
            },
        )
    }

    @Hikagable
    private fun Hikage.Performer<LinearLayout.LayoutParams>.buildSectionLabel(text: String) {
        MaterialTextView(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = dp(12)
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

    private inner class MemberAdapter : RecyclerView.Adapter<MemberAdapter.Holder>() {
        private var members: List<GroupMemberRepository.Member> = emptyList()
        private var canManage = false
        private var actorRole: MemberRole? = null
        private var actionUid: String? = null

        @SuppressLint("NotifyDataSetChanged")
        fun submit(
            members: List<GroupMemberRepository.Member>,
            canManage: Boolean,
            actorRole: MemberRole?,
            actionUid: String?,
        ) {
            this.members = members
            this.canManage = canManage
            this.actorRole = actorRole
            this.actionUid = actionUid
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            lateinit var avatar: ShapeableImageView
            lateinit var name: MaterialTextView
            lateinit var role: MaterialTextView
            lateinit var kickButton: com.google.android.material.button.MaterialButton
            val hikage = Hikagable(parent.context) {
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                    },
                ) {
                    avatar = ShapeableImageView(
                        lparams = LayoutParams(width = dp(44), height = dp(44)),
                        init = {
                            setImageResource(R.drawable.ic_account_circle)
                            AvatarLoader.makeCircular(this)
                            scaleType = ImageView.ScaleType.CENTER_CROP
                        },
                    )
                    LinearLayout(
                        lparams = LayoutParams(
                            width = 0,
                            height = ViewGroup.LayoutParams.WRAP_CONTENT,
                        ) {
                            weight = 1f
                            marginStart = dp(12)
                        },
                        init = { orientation = LinearLayout.VERTICAL },
                    ) {
                        name = MaterialTextView(
                            lparams = LayoutParams(widthMatchParent = true),
                            init = {
                                TextViewCompat.setTextAppearance(
                                    this,
                                    com.google.android.material.R.style.TextAppearance_Material3_BodyLarge,
                                )
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                            },
                        )
                        role = MaterialTextView(
                            lparams = LayoutParams(widthMatchParent = true) {
                                topMargin = dp(2)
                            },
                            init = {
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
                                maxLines = 1
                                ellipsize = TextUtils.TruncateAt.END
                            },
                        )
                    }
                    kickButton = IconButton(
                        lparams = LayoutParams(width = dp(40), height = dp(40)),
                        init = {
                            icon = drawableResource(R.drawable.ic_delete)
                            contentDescription = "移出群聊"
                            visibility = View.GONE
                        },
                    )
                }
            }
            val root = hikage.root as LinearLayout
            root.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            return Holder(root, avatar, name, role, kickButton)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val member = members[position]
            holder.name.text = member.displayName
            holder.role.text = memberRoleLabel(member.role)
            holder.avatar.contentDescription = "${member.displayName} 的头像"
            AvatarLoader.bind(
                imageView = holder.avatar,
                localPath = member.avatarPath,
                urls = AvatarSources.forSenderUin(member.uin),
                fallback = holder.itemView.context.getDrawableCompat(R.drawable.ic_account_circle),
            )

            val kickable = canKickMember(canManage, actorRole, member.role)
            holder.kickButton.visibility = if (kickable) View.VISIBLE else View.GONE
            holder.kickButton.isEnabled = actionUid == null
            holder.kickButton.setOnClickListener { confirmKick(member) }
            holder.itemView.setOnLongClickListener {
                if (kickable) {
                    confirmKick(member)
                    true
                } else {
                    false
                }
            }
        }

        override fun onViewRecycled(holder: Holder) {
            AvatarLoader.unbind(holder.avatar)
            super.onViewRecycled(holder)
        }

        override fun getItemCount(): Int = members.size

        inner class Holder(
            itemView: View,
            val avatar: ShapeableImageView,
            val name: MaterialTextView,
            val role: MaterialTextView,
            val kickButton: com.google.android.material.button.MaterialButton,
        ) : RecyclerView.ViewHolder(itemView)
    }

    private fun canKickMember(
        canManage: Boolean,
        actorRole: MemberRole?,
        targetRole: String,
    ): Boolean {
        if (!canManage) return false
        val normalized = targetRole.uppercase()
        if (normalized == MemberRole.OWNER.name) return false
        if (actorRole == MemberRole.ADMIN && normalized == MemberRole.ADMIN.name) return false
        return true
    }

    private fun memberRoleLabel(role: String): String = when (role.uppercase()) {
        MemberRole.OWNER.name -> "群主"
        MemberRole.ADMIN.name -> "管理员"
        MemberRole.MEMBER.name -> "成员"
        MemberRole.STRANGER.name -> "陌生人"
        else -> role.ifBlank { "成员" }
    }

    private fun formatBulletinTime(createTime: Int): String {
        if (createTime <= 0) return ""
        return runCatching {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date(createTime.toLong() * 1000L))
        }.getOrDefault("")
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
