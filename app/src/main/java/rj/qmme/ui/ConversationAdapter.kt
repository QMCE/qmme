package rj.qmme.ui

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.listitem.ListItemCardView
import com.google.android.material.listitem.ListItemViewHolder
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.textview.MaterialTextView
import com.highcapable.betterandroid.ui.extension.component.base.getDrawableCompat
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.LinearLayout
import com.highcapable.hikage.widget.com.google.android.material.divider.MaterialDivider
import com.highcapable.hikage.widget.com.google.android.material.imageview.ShapeableImageView
import com.highcapable.hikage.widget.com.google.android.material.textview.MaterialTextView
import com.tencent.qqnt.avatar.WatchAvatarView
import com.tencent.qqnt.kernel.nativeinterface.RecentContactInfo
import rj.qmme.R
import rj.qmme.viewmodel.ChatListViewModel
import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import rj.qmme.ui.hikage.ListItemLayout as HListItemLayout
import rj.qmme.ui.hikage.SegmentedListItemCardView as HSegmentedListItemCardView

/** Official Material 3 segmented ListItemLayout for the conversation feed. */
class ConversationAdapter(
    private val viewModel: ChatListViewModel,
    private val onClick: (RecentContactInfo) -> Unit,
) : RecyclerView.Adapter<ConversationAdapter.Holder>() {
    /**
     * Everything one row draws, resolved once per publish.  Keeping it out of
     * [onBindViewHolder] lets an incoming snapshot be compared for real instead
     * of being taken on faith, and keeps the reflective preview lookup off the
     * scroll path.
     */
    private class Row(
        val id: Long,
        val contact: RecentContactInfo,
        val title: String,
        val preview: String,
        val time: String,
        val unread: Long,
        val avatarPath: String,
        val avatarUrls: List<String>,
        val peerUin: Long,
        val peerUid: String,
    ) {
        fun sameContentAs(other: Row): Boolean =
            title == other.title &&
                preview == other.preview &&
                time == other.time &&
                unread == other.unread &&
                avatarPath == other.avatarPath &&
                avatarUrls == other.avatarUrls
    }

    private var rows: List<Row> = emptyList()

    init {
        // Stable ids keep a conversation on the same ViewHolder across updates,
        // so a re-published list cannot shuffle rows through each other's views.
        setHasStableIds(true)
    }

    /**
     * The recent-contact StateFlow re-emits on every publish, and returning
     * from a chat re-subscribes the collector and replays the current value.
     * A blanket notifyDataSetChanged() there rebinds every visible row, which
     * re-resolved each row's segmented shape and reset each avatar — the rows
     * then settled one by one, which is the twitching this diff removes.
     */
    @SuppressLint("NotifyDataSetChanged")
    fun submitList(next: List<RecentContactInfo>) {
        val previous = rows
        val updated = next.map(::rowOf)
        if (isUnchanged(previous, updated)) return
        rows = updated
        if (previous.isEmpty() || updated.isEmpty()) {
            notifyDataSetChanged()
            return
        }
        DiffUtil.calculateDiff(RowDiff(previous, updated)).dispatchUpdatesTo(this)
        // First/middle/last shape and the trailing divider are a function of the
        // row's place in the whole list, and neither a move nor a shift caused by
        // an insert rebinds the rows it displaces. Only the two ends can change
        // shape, so re-resolving them when a different conversation lands there
        // is enough — every row in between is POSITION_MIDDLE either way.
        val endsChanged = previous.size != updated.size ||
            previous.first().id != updated.first().id ||
            previous.last().id != updated.last().id
        if (endsChanged) {
            notifyItemChanged(0)
            notifyItemChanged(updated.lastIndex)
        }
    }

    private fun isUnchanged(previous: List<Row>, updated: List<Row>): Boolean =
        previous.size == updated.size &&
            previous.indices.all { index ->
                previous[index].id == updated[index].id &&
                    previous[index].sameContentAs(updated[index])
            }

    private fun rowOf(contact: RecentContactInfo) = Row(
        id = identityOf(contact),
        contact = contact,
        title = contact.remark.orEmpty().ifBlank {
            contact.peerName.orEmpty().ifBlank {
                contact.peerUin.takeIf { it != 0L }?.toString() ?: "QQ 会话"
            }
        },
        preview = viewModel.previewFor(contact),
        time = formatTime(contact.msgTime),
        unread = contact.unreadCnt,
        avatarPath = contact.avatarPath.orEmpty(),
        avatarUrls = AvatarSources.forRecent(contact),
        peerUin = contact.peerUin.takeIf { it > 0L } ?: 0L,
        peerUid = contact.peerUid.orEmpty(),
    )

    /** Mirrors ChatListViewModel's cache key so both sides agree on identity. */
    private fun identityOf(contact: RecentContactInfo): Long {
        if (contact.contactId != 0L) return contact.contactId
        return contact.peerUid?.hashCode()?.toLong()
            ?: contact.id.orEmpty().hashCode().toLong()
    }

    private class RowDiff(
        private val previous: List<Row>,
        private val updated: List<Row>,
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = previous.size

        override fun getNewListSize(): Int = updated.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            previous[oldItemPosition].id == updated[newItemPosition].id

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
            previous[oldItemPosition].sameContentAs(updated[newItemPosition])
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
                        // Do NOT override cardBackgroundColor here. The segmented
                        // style already resolves to a state-aware selector
                        // (m3_segmented_list_item_background_color_selector) whose
                        // default is ?attr/colorSurface. Setting a flat color
                        // silently discards the checked / swiped state colors.
                        isClickable = true
                        isFocusable = true
                        isSwipeEnabled = false
                    },
                ) {
                    LinearLayout(
                        lparams = LayoutParams(matchParent = true),
                        init = { orientation = LinearLayout.VERTICAL },
                    ) {
                        LinearLayout(
                            lparams = LayoutParams(widthMatchParent = true),
                            init = {
                                orientation = LinearLayout.HORIZONTAL
                                // Keep the timestamp's top edge in the same band as
                                // the title whether or not the unread badge exists.
                                gravity = Gravity.TOP
                                setPadding(
                                    dp(parent, 12),
                                    dp(parent, 10),
                                    dp(parent, 12),
                                    dp(parent, 10)
                                )
                            },
                        ) {
                            avatar = ShapeableImageView(
                                lparams = LayoutParams(
                                    width = dp(parent, 46),
                                    height = dp(parent, 46)
                                ),
                                init = {
                                    setImageResource(R.drawable.ic_launcher_foreground)
                                    AvatarLoader.makeCircular(this)
                                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                                    contentDescription = "QQ 会话头像"
                                },
                            )
                            LinearLayout(
                                lparams = LayoutParams(
                                    width = 0,
                                    height = ViewGroup.LayoutParams.WRAP_CONTENT,
                                ) {
                                    weight = 1f
                                    marginStart = dp(parent, 12)
                                },
                                init = { orientation = LinearLayout.VERTICAL },
                            ) {
                                title = MaterialTextView(
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
                                preview = MaterialTextView(
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
                            LinearLayout(
                                lparams = LayoutParams(height = ViewGroup.LayoutParams.WRAP_CONTENT),
                                init = {
                                    orientation = LinearLayout.VERTICAL
                                    gravity = Gravity.END
                                },
                            ) {
                                time = MaterialTextView(
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
                                unread = MaterialTextView(
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
                                        // M3 badges are error-colored; the previous
                                        // primaryContainer pair was too low-contrast
                                        // to read as an alert at 10sp.
                                        textColor = MaterialColors.getColor(
                                            this,
                                            com.google.android.material.R.attr.colorOnError,
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
                                                    this@MaterialTextView,
                                                    androidx.appcompat.R.attr.colorError,
                                                ),
                                            )
                                        }
                                        visibility = View.GONE
                                    },
                                )
                            }
                        }
                        divider = MaterialDivider(
                            lparams = LayoutParams(
                                widthMatchParent = true,
                                height = dp(parent, 2),
                            ),
                            init = {
                                // Use a narrow surface-colored seam instead of a visible
                                // gray rule: it keeps the grouped M3 card readable without
                                // introducing a hard divider line.
                                dividerThickness = dp(parent, 2)
                                dividerInsetStart = 0
                                dividerInsetEnd = 0
                                dividerColor = MaterialColors.getColor(
                                    this,
                                    com.google.android.material.R.attr.colorSurfaceContainer,
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
            marginStart = dp(parent, 8)
            marginEnd = dp(parent, 8)
        }
        return Holder(root, itemCard, avatar, title, preview, time, unread, divider, officialAvatar)
    }

    override fun getItemId(position: Int): Long = rows[position].id

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        // All conversations are one segmented group, so the official holder
        // supplies first/middle/last shape states using the full item count.
        holder.bind(position, rows.size)
        holder.title.text = row.title
        holder.preview.text = row.preview
        holder.time.text = row.time
        holder.unread.text = if (row.unread > 0L) formatUnreadCount(row.unread) else ""
        holder.unread.contentDescription = row.unread.takeIf { it > 0L }
            ?.let { "$it 条未读消息" }
        holder.unread.visibility = if (row.unread > 0L) View.VISIBLE else View.GONE
        holder.divider.visibility = if (position < rows.lastIndex) View.VISIBLE else View.GONE
        holder.avatar.scaleType = ImageView.ScaleType.CENTER_CROP
        AvatarLoader.bind(
            imageView = holder.avatar,
            localPath = row.avatarPath,
            urls = row.avatarUrls,
            fallback = holder.itemView.context.getDrawableCompat(R.drawable.ic_launcher_foreground),
        )
        OfficialAvatarLoader.bind(holder.officialAvatar, row.peerUin, row.peerUid)
        holder.itemCard.setOnClickListener { onClick(row.contact) }
    }

    override fun onViewRecycled(holder: Holder) {
        AvatarLoader.unbind(holder.avatar)
        OfficialAvatarLoader.unbind(holder.officialAvatar)
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = rows.size

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

    /**
     * IM-convention timestamps: today -> clock time, yesterday -> "昨天",
     * this week -> weekday, older -> date. A raw clock time on a week-old
     * conversation reads as noise.
     */
    private fun formatTime(timestamp: Long): String {
        if (timestamp <= 0L) return ""
        val millis = if (timestamp < 10_000_000_000L) timestamp * 1000L else timestamp
        val then = Calendar.getInstance().apply { timeInMillis = millis }
        val now = Calendar.getInstance()

        fun Calendar.startOfDay(): Long = (clone() as Calendar).run {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            timeInMillis
        }
        val dayDiff = ((now.startOfDay() - then.startOfDay()) / MILLIS_PER_DAY).toInt()

        return when {
            dayDiff <= 0 -> DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))
            dayDiff == 1 -> "昨天"
            dayDiff < 7 -> WEEKDAYS[then.get(Calendar.DAY_OF_WEEK) - 1]
            then.get(Calendar.YEAR) == now.get(Calendar.YEAR) ->
                "${then.get(Calendar.MONTH) + 1}月${then.get(Calendar.DAY_OF_MONTH)}日"
            else ->
                "${then.get(Calendar.YEAR)}/${then.get(Calendar.MONTH) + 1}/${then.get(Calendar.DAY_OF_MONTH)}"
        }
    }

    private companion object {
        const val MAX_COMPACT_UNREAD_COUNT = 99L
        const val MILLIS_PER_DAY = 86_400_000L
        val WEEKDAYS = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")
    }
}
