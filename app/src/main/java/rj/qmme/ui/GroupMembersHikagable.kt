package rj.qmme.ui

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textview.MaterialTextView
import com.highcapable.betterandroid.ui.extension.component.base.getDrawableCompat
import com.highcapable.hikage.annotation.Hikagable
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.LinearLayout
import com.highcapable.hikage.widget.androidx.recyclerview.widget.RecyclerView
import com.highcapable.hikage.widget.androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.highcapable.hikage.widget.com.google.android.material.appbar.MaterialToolbar
import com.highcapable.hikage.widget.com.google.android.material.imageview.ShapeableImageView
import com.highcapable.hikage.widget.com.google.android.material.textview.MaterialTextView
import androidx.core.widget.TextViewCompat
import rj.qmme.R
import rj.qmme.data.chat.GroupMemberRepository

/** Group member list screen with pull-to-refresh. */
class GroupMembersHikagable(
    private val context: Context,
    private val groupCode: Long,
    private val groupTitle: String,
    private val onBack: () -> Unit,
    private val onOpenMember: (uid: String, uin: Long, name: String, avatarPath: String) -> Unit,
) : HikageScreen {
    private lateinit var toolbar: MaterialToolbar
    private lateinit var statusText: MaterialTextView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var recyclerView: RecyclerView
    private val adapter = MemberAdapter()
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
                toolbar = MaterialToolbar(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        title = "群成员"
                        subtitle = groupTitle
                        navigationIcon = drawableResource(R.drawable.ic_arrow_back)
                        setNavigationContentDescription("返回")
                        setNavigationOnClickListener { onBack() }
                        EdgeToEdgeInsets.applyHorizontalInsets(this)
                    },
                )
                statusText = MaterialTextView(
                    lparams = LayoutParams(widthMatchParent = true) {
                        marginStart = dp(16)
                        marginEnd = dp(16)
                        topMargin = dp(8)
                    },
                    init = {
                        text = "正在加载…"
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
                        EdgeToEdgeInsets.applyHorizontalInsets(this)
                    },
                )
                swipeRefresh = SwipeRefreshLayout(
                    lparams = LayoutParams(widthMatchParent = true, height = 0) { weight = 1f },
                    init = {
                        setOnRefreshListener { loadMembers(forceRefresh = true) }
                    },
                ) {
                    recyclerView = RecyclerView(
                        lparams = LayoutParams(matchParent = true),
                        init = {
                            layoutManager = LinearLayoutManager(context)
                            adapter = this@GroupMembersHikagable.adapter
                            clipToPadding = false
                            setPadding(dp(8), dp(4), dp(8), dp(8))
                        },
                    )
                }
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true, height = 0),
                    init = { EdgeToEdgeInsets.applyBottomInsetSpacer(this) },
                )
            }
        }.also { cachedHikage = it }

    fun bind(owner: LifecycleOwner) {
        if (bound) return
        bound = true
        loadMembers(forceRefresh = false)
    }

    private fun loadMembers(forceRefresh: Boolean) {
        swipeRefresh.isRefreshing = true
        statusText.text = "正在加载…"
        GroupMemberRepository.load(groupCode, forceRefresh) { members, error ->
            swipeRefresh.isRefreshing = false
            if (members != null) {
                adapter.submit(members)
                statusText.text = "${members.size} 位成员"
            } else {
                adapter.submit(emptyList())
                statusText.text = error?.takeIf { it.isNotBlank() } ?: "加载失败"
            }
        }
    }

    private inner class MemberAdapter :
        RecyclerView.Adapter<MemberAdapter.Holder>() {
        private var members: List<GroupMemberRepository.Member> = emptyList()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<GroupMemberRepository.Member>) {
            members = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            lateinit var avatar: ShapeableImageView
            lateinit var name: MaterialTextView
            lateinit var role: MaterialTextView
            val hikage = Hikagable(parent.context) {
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        isClickable = true
                        isFocusable = true
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
                }
            }
            val root = hikage.root as LinearLayout
            root.layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            return Holder(root, avatar, name, role)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val member = members[position]
            holder.name.text = member.displayName
            holder.role.text = member.role.ifBlank { member.uin.takeIf { it > 0L }?.toString().orEmpty() }
            holder.avatar.contentDescription = "${member.displayName} 的头像"
            AvatarLoader.bind(
                imageView = holder.avatar,
                localPath = member.avatarPath,
                urls = AvatarSources.forSenderUin(member.uin),
                fallback = holder.itemView.context.getDrawableCompat(R.drawable.ic_account_circle),
            )
            holder.itemView.setOnClickListener {
                onOpenMember(member.uid, member.uin, member.displayName, member.avatarPath)
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
        ) : RecyclerView.ViewHolder(itemView)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
