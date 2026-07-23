package rj.qmme.ui

import android.text.TextUtils
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
import com.google.android.material.textview.MaterialTextView
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.LinearLayout as HLinearLayout
import com.highcapable.hikage.widget.com.google.android.material.divider.MaterialDivider as HMaterialDivider
import com.highcapable.hikage.widget.com.google.android.material.imageview.ShapeableImageView as HShapeableImageView
import com.highcapable.hikage.widget.com.google.android.material.textview.MaterialTextView as HMaterialTextView
import com.tencent.qqnt.avatar.WatchAvatarView
import rj.qmme.R
import rj.qmme.ui.hikage.SegmentedListItemCardView as HSegmentedListItemCardView
import rj.qmme.ui.hikage.ListItemLayout as HListItemLayout
import rj.qmme.viewmodel.ContactsViewModel

/**
 * Official Material 3 segmented list items. Each contact group is one visual
 * card: ListItemLayout supplies first/middle/last shapes and the full-width
 * divider inside the ListItemCardView splits the group into real list rows.
 */
class ContactsAdapter(
    private val onClick: (ContactsViewModel.UiBuddy) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private sealed interface Row {
        data class Header(val category: ContactsViewModel.UiCategory) : Row

        data class Buddy(
            val buddy: ContactsViewModel.UiBuddy,
            val indexInCategory: Int,
            val categorySize: Int,
        ) : Row
    }

    private var rows: List<Row> = emptyList()

    fun submitCategories(categories: List<ContactsViewModel.UiCategory>) {
        rows = buildList {
            categories.forEach { category ->
                add(Row.Header(category))
                category.buddies.forEachIndexed { index, buddy ->
                    add(
                        Row.Buddy(
                            buddy = buddy,
                            indexInCategory = index,
                            categorySize = category.buddies.size,
                        ),
                    )
                }
            }
        }
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (rows[position]) {
        is Row.Header -> VIEW_TYPE_HEADER
        is Row.Buddy -> VIEW_TYPE_BUDDY
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
        if (viewType == VIEW_TYPE_HEADER) createHeaderHolder(parent) else createBuddyHolder(parent)

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).title.text =
                "${row.category.name} (${row.category.buddies.size})"

            is Row.Buddy -> {
                val buddyHolder = holder as BuddyHolder
                val buddy = row.buddy
                // This invokes ListItemLayout.updateAppearance(), which changes
                // the M3 card shape to first / middle / last within this category.
                buddyHolder.bind(row.indexInCategory, row.categorySize)
                buddyHolder.title.text = buddy.remark.ifBlank { buddy.nick }
                buddyHolder.subtitle.text = buildString {
                    if (buddy.nick.isNotBlank() && buddy.nick != buddy.remark) append(buddy.nick)
                    if (buddy.uin > 0L) {
                        if (isNotEmpty()) append("  ·  ")
                        append(buddy.uin)
                    }
                }.ifBlank { buddy.uid }
                buddyHolder.avatar.contentDescription = "${buddy.nick} 的头像"
                buddyHolder.avatar.scaleType = ImageView.ScaleType.CENTER_CROP
                buddyHolder.divider.visibility = if (row.indexInCategory < row.categorySize - 1) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
                AvatarLoader.bind(
                    imageView = buddyHolder.avatar,
                    localPath = buddy.avatarPath,
                    urls = AvatarSources.forBuddy(buddy),
                    fallback = ContextCompat.getDrawable(
                        buddyHolder.itemView.context,
                        R.drawable.ic_launcher_foreground,
                    ),
                )
                OfficialAvatarLoader.bind(buddyHolder.officialAvatar, buddy.uin, buddy.uid)
                buddyHolder.itemCard.setOnClickListener { onClick(buddy) }
            }
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is BuddyHolder) {
            AvatarLoader.unbind(holder.avatar)
            OfficialAvatarLoader.unbind(holder.officialAvatar)
        }
        super.onViewRecycled(holder)
    }

    override fun getItemCount(): Int = rows.size

    private fun createHeaderHolder(parent: ViewGroup): HeaderHolder {
        lateinit var title: MaterialTextView
        val hikage = Hikagable(parent.context) {
            HLinearLayout(
                lparams = LayoutParams(widthMatchParent = true),
                init = { orientation = LinearLayout.VERTICAL },
            ) {
                title = HMaterialTextView(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        TextViewCompat.setTextAppearance(
                            this,
                            com.google.android.material.R.style.TextAppearance_Material3_LabelLarge,
                        )
                        setPadding(dp(parent, 12), dp(parent, 16), dp(parent, 12), dp(parent, 6))
                    },
                )
            }
        }
        val root = hikage.root as LinearLayout
        root.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        return HeaderHolder(root, title)
    }

    private fun createBuddyHolder(parent: ViewGroup): BuddyHolder {
        lateinit var itemCard: ListItemCardView
        lateinit var avatar: ShapeableImageView
        lateinit var title: MaterialTextView
        lateinit var subtitle: MaterialTextView
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
                        // The official style has M3 list-item state colors and
                        // shape states. Removing default content padding lets the
                        // divider travel across the entire visual card.
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
                                gravity = Gravity.CENTER_VERTICAL
                                setPadding(dp(parent, 12), dp(parent, 10), dp(parent, 12), dp(parent, 10))
                            },
                        ) {
                            avatar = HShapeableImageView(
                                lparams = LayoutParams(width = dp(parent, 44), height = dp(parent, 44)),
                                init = {
                                    setImageResource(R.drawable.ic_launcher_foreground)
                                    AvatarLoader.makeCircular(this)
                                    scaleType = ImageView.ScaleType.CENTER_INSIDE
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
                                subtitle = HMaterialTextView(
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
                        }
                        divider = HMaterialDivider(lparams = LayoutParams(widthMatchParent = true))
                    }
                }
            }
        }
        val root = hikage.root as ViewGroup
        val officialAvatar = WatchAvatarViewFactory.create(parent.context).apply {
            // Keep QQ's official cache warming without adding a non-list-item
            // direct child to ListItemLayout (which reserves direct children for
            // the card and optional reveal surfaces).
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
        return BuddyHolder(root, itemCard, avatar, title, subtitle, divider, officialAvatar)
    }

    class HeaderHolder(itemView: View, val title: MaterialTextView) : RecyclerView.ViewHolder(itemView)

    class BuddyHolder(
        itemView: View,
        val itemCard: ListItemCardView,
        val avatar: ShapeableImageView,
        val title: MaterialTextView,
        val subtitle: MaterialTextView,
        val divider: MaterialDivider,
        val officialAvatar: WatchAvatarView,
    ) : ListItemViewHolder(itemView)

    private fun dp(parent: ViewGroup, value: Int): Int =
        (value * parent.resources.displayMetrics.density).toInt()

    companion object {
        private const val VIEW_TYPE_HEADER = 0
        private const val VIEW_TYPE_BUDDY = 1
    }
}
