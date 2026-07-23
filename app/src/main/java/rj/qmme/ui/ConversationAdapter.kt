package rj.qmme.ui

import android.content.res.ColorStateList
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.listitem.ListItemCardView
import com.google.android.material.listitem.ListItemViewHolder
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.textview.MaterialTextView
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.LinearLayout as HLinearLayout
import com.highcapable.hikage.widget.com.google.android.material.divider.MaterialDivider as HMaterialDivider
import com.highcapable.hikage.widget.com.google.android.material.imageview.ShapeableImageView as HShapeableImageView
import com.highcapable.hikage.widget.com.google.android.material.textview.MaterialTextView as HMaterialTextView
import com.tencent.qqnt.avatar.WatchAvatarView
import com.tencent.qqnt.kernel.nativeinterface.RecentContactInfo
import rj.qmme.R
import rj.qmme.ui.hikage.SegmentedListItemCardView as HSegmentedListItemCardView
import rj.qmme.ui.hikage.ListItemLayout as HListItemLayout
import rj.qmme.viewmodel.ChatListViewModel
import java.text.DateFormat
import java.util.Date

/** Official Material 3 segmented ListItemLayout for the conversation feed. */
class ConversationAdapter(
    private val viewModel: ChatListViewModel,
    private val onClick: (RecentContactInfo) -> Unit,
) : RecyclerView.Adapter<ConversationAdapter.Holder>() {
    private var items: List<RecentContactInfo> = emptyList()

    fun submitList(next: List<RecentContactInfo>) {
        items = next
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        lateinit var itemCard: ListItemCardView
        lateinit var avatar: ShapeableImageView
        lateinit var title: MaterialTextView
        lateinit var preview: MaterialTextView
        lateinit var time: MaterialTextView
        lateinit var unread: MaterialTextView
        lateinit var divider: MaterialDivider

        val hikage = Hikagable(parent.context) {
            HListItemLayout(
                lparams = LayoutParams(widthMatchParent = true),
                init = {
                    clipToPadding = false
                    clipChildren = false
                },
            ) {
                itemCard = HSegmentedListItemCardView(
                    lparams = LayoutParams(matchParent = true),
                    init = {
                        setContentPadding(0, 0, 0, 0)
                        // Dynamic M3 surface-container color makes the full
                        // first/middle/last run read as one grouped card.
                        setCardBackgroundColor(
                            MaterialColors.getColor(
                                this,
                                com.google.android.material.R.attr.colorSurfaceContainer,
                            ),
                        )
                        isClickable = true
                        isFocusable = true
                        setSwipeEnabled(false)
                    },
                ) {
                    HLinearLayout(
                        lparams = LayoutParams(matchParent = true),
                        init = { orientation = LinearLayout.VERTICAL },
                    ) {
                        HLinearLayout(
                            lparams = LayoutParams(widthMatchParent = true),
                            init = {
                                orientation = LinearLayout.HORIZONTAL
                                // Keep the timestamp's top edge in the same band as
                                // the title whether or not the unread badge exists.
                                gravity = Gravity.TOP
                                setPadding(dp(parent, 12), dp(parent, 10), dp(parent, 12), dp(parent, 10))
                            },
                        ) {
                            avatar = HShapeableImageView(
                                lparams = LayoutParams(width = dp(parent, 46), height = dp(parent, 46)),
                                init = {
                                    setImageResource(R.drawable.ic_launcher_foreground)
                                    AvatarLoader.makeCircular(this)
                                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                                    contentDescription = "QQ 会话头像"
                                },
                            )
                            HLinearLayout(
                                lparams = LayoutParams(
                                    width = 0,
                                    height = ViewGroup.LayoutParams.WRAP_CONTENT,
                                ) {
                                    weight = 1f
                                    leftMargin = dp(parent, 12)
                                },
                                init = { orientation = LinearLayout.VERTICAL },
                            ) {
                                title = HMaterialTextView(
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
                                preview = HMaterialTextView(
                                    lparams = LayoutParams(widthMatchParent = true) {
                                        topMargin = dp(parent, 2)
                                    },
                                    init = {
                                        TextViewCompat.setTextAppearance(
                                            this,
                                            com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                                        )
                                        maxLines = 1
                                        ellipsize = TextUtils.TruncateAt.END
                                    },
                                )
                            }
                            HLinearLayout(
                                lparams = LayoutParams(height = ViewGroup.LayoutParams.WRAP_CONTENT),
                                init = {
                                    orientation = LinearLayout.VERTICAL
                                    gravity = Gravity.END
                                },
                            ) {
                                time = HMaterialTextView(
                                    lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT) {
                                        // LabelSmall's baseline is higher than BodyLarge;
                                        // this small offset lines it up with the title band.
                                        topMargin = dp(parent, 2)
                                    },
                                    init = {
                                        TextViewCompat.setTextAppearance(
                                            this,
                                            com.google.android.material.R.style.TextAppearance_Material3_LabelSmall,
                                        )
                                        gravity = Gravity.END
                                    },
                                )
                                unread = HMaterialTextView(
                                    lparams = LayoutParams(
                                        width = ViewGroup.LayoutParams.WRAP_CONTENT,
                                        // M3 text badges are 16dp tall. Keep the exact size
                                        // here so the count does not expand the time column.
                                        height = dp(parent, 16),
                                    ) {
                                        topMargin = dp(parent, 2)
                                    },
                                    init = {
                                        // Do not use Chip for a passive inline badge: ChipDrawable
                                        // owns its own text painter and was swallowing the glyphs
                                        // at this compact height. MaterialTextView keeps this as a
                                        // normal, reliably measured M3 text component.
                                        TextViewCompat.setTextAppearance(
                                            this,
                                            com.google.android.material.R.style.TextAppearance_Material3_LabelSmall,
                                        )
                                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                                        setTextColor(
                                            MaterialColors.getColor(
                                                this,
                                                com.google.android.material.R.attr.colorOnPrimaryContainer,
                                            ),
                                        )
                                        gravity = Gravity.CENTER
                                        isSingleLine = true
                                        includeFontPadding = false
                                        minWidth = 0
                                        minHeight = 0
                                        setPadding(dp(parent, 4), 0, dp(parent, 4), 0)
                                        background = MaterialShapeDrawable(
                                            ShapeAppearanceModel.builder()
                                                .setAllCornerSizes(dp(parent, 8).toFloat())
                                                .build(),
                                        ).apply {
                                            fillColor = ColorStateList.valueOf(
                                                MaterialColors.getColor(
                                                    this@HMaterialTextView,
                                                    com.google.android.material.R.attr.colorPrimaryContainer,
                                                ),
                                            )
                                        }
                                        visibility = View.GONE
                                    },
                                )
                            }
                        }
                        divider = HMaterialDivider(
                            lparams = LayoutParams(
                                widthMatchParent = true,
                                height = dp(parent, 2),
                            ),
                            init = {
                                // Use a narrow surface-colored seam instead of a visible
                                // gray rule: it keeps the grouped M3 card readable without
                                // introducing a hard divider line.
                                setDividerThickness(dp(parent, 2))
                                setDividerInsetStart(0)
                                setDividerInsetEnd(0)
                                setDividerColor(
                                    MaterialColors.getColor(
                                        this,
                                        com.google.android.material.R.attr.colorSurface,
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }
        val root = hikage.root as ViewGroup
        val officialAvatar = WatchAvatarViewFactory.create(parent.context).apply {
            // Keep the official QQ avatar loader alive without violating
            // ListItemLayout's direct-child contract.
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        itemCard.addView(officialAvatar, FrameLayout.LayoutParams(1, 1))
        root.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            leftMargin = dp(parent, 8)
            rightMargin = dp(parent, 8)
        }
        return Holder(root, itemCard, avatar, title, preview, time, unread, divider, officialAvatar)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val contact = items[position]
        // All conversations are one segmented group, so the official holder
        // supplies first/middle/last shape states using the full item count.
        holder.bind(position, items.size)
        val title = contact.remark.orEmpty().ifBlank {
            contact.peerName.orEmpty().ifBlank {
                contact.peerUin.takeIf { it != 0L }?.toString() ?: "QQ 会话"
            }
        }
        holder.title.text = title
        holder.preview.text = viewModel.previewFor(contact)
        holder.time.text = formatTime(contact.msgTime)
        val unreadCount = contact.unreadCnt
        holder.unread.text = if (unreadCount > 0L) formatUnreadCount(unreadCount) else ""
        holder.unread.contentDescription = unreadCount.takeIf { it > 0L }
            ?.let { "$it 条未读消息" }
        holder.unread.visibility = if (unreadCount > 0L) View.VISIBLE else View.GONE
        holder.divider.visibility = if (position < items.lastIndex) View.VISIBLE else View.GONE
        holder.avatar.scaleType = ImageView.ScaleType.CENTER_CROP
        AvatarLoader.bind(
            imageView = holder.avatar,
            localPath = contact.avatarPath,
            urls = AvatarSources.forRecent(contact),
            fallback = ContextCompat.getDrawable(holder.itemView.context, R.drawable.ic_launcher_foreground),
        )
        OfficialAvatarLoader.bind(
            holder.officialAvatar,
            contact.peerUin.takeIf { it > 0L }.orZero(),
            contact.peerUid.orEmpty(),
        )
        holder.itemCard.setOnClickListener { onClick(contact) }
    }

    override fun onViewRecycled(holder: Holder) {
        AvatarLoader.unbind(holder.avatar)
        OfficialAvatarLoader.unbind(holder.officialAvatar)
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = items.size

    class Holder(
        itemView: View,
        val itemCard: ListItemCardView,
        val avatar: ShapeableImageView,
        val title: MaterialTextView,
        val preview: MaterialTextView,
        val time: MaterialTextView,
        val unread: MaterialTextView,
        val divider: MaterialDivider,
        val officialAvatar: WatchAvatarView,
    ) : ListItemViewHolder(itemView)

    private fun dp(parent: ViewGroup, value: Int): Int =
        (value * parent.resources.displayMetrics.density).toInt()

    private fun formatUnreadCount(count: Long): String =
        if (count > MAX_COMPACT_UNREAD_COUNT) "$MAX_COMPACT_UNREAD_COUNT+" else count.toString()

    private fun Long?.orZero(): Long = this ?: 0L

    private fun formatTime(timestamp: Long): String {
        if (timestamp <= 0L) return ""
        val millis = if (timestamp < 10_000_000_000L) timestamp * 1000L else timestamp
        return DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
    }

    private companion object {
        const val MAX_COMPACT_UNREAD_COUNT = 99L
    }
}
