package rj.qmme.ui

import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.textview.MaterialTextView
import com.google.android.material.imageview.ShapeableImageView
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.widget.android.widget.FrameLayout as HFrameLayout
import com.highcapable.hikage.widget.android.widget.LinearLayout as HLinearLayout
import com.highcapable.hikage.widget.com.google.android.material.card.MaterialCardView as HMaterialCardView
import com.highcapable.hikage.widget.com.google.android.material.textview.MaterialTextView as HMaterialTextView
import com.highcapable.hikage.widget.com.google.android.material.imageview.ShapeableImageView as HShapeableImageView
import rj.qmme.R
import rj.qmme.viewmodel.ChatDetailViewModel
import java.util.Date

/** Material 3 Expressive message rows: grouped by sender, list-flavored, time on the meta row. */
class MessageAdapter(
    private val isGroup: Boolean = false,
    private val onImageClick: (ChatDetailViewModel.UiImage) -> Unit = {},
    private val onMessageLongClick: (ChatDetailViewModel.UiMessage) -> Unit = {},
) : ListAdapter<ChatDetailViewModel.UiMessage, MessageAdapter.Holder>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        lateinit var rowContainer: LinearLayout
        lateinit var avatar: ShapeableImageView
        lateinit var metaRow: LinearLayout
        lateinit var nickname: MaterialTextView
        lateinit var time: MaterialTextView
        lateinit var card: MaterialCardView
        lateinit var image: ShapeableImageView
        lateinit var body: MaterialTextView
        val maxBubbleWidth = (parent.resources.displayMetrics.widthPixels * 0.72f).toInt()
        val imageWidth = minOf(dp(parent, 240), maxBubbleWidth)
        val hikage = Hikagable {
            HFrameLayout(
                lparams = LayoutParams(widthMatchParent = true),
                init = {
                    setPadding(dp(parent, 16), dp(parent, 1), dp(parent, 16), dp(parent, 1))
                },
            ) {
                rowContainer = HLinearLayout(
                    lparams = LayoutParams(
                        width = ViewGroup.LayoutParams.WRAP_CONTENT,
                        height = ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                    init = {
                        orientation = LinearLayout.HORIZONTAL
                    },
                ) {
                    avatar = HShapeableImageView(
                        lparams = LayoutParams(width = dp(parent, 36), height = dp(parent, 36)) {
                            gravity = Gravity.TOP
                            rightMargin = dp(parent, 8)
                        },
                        init = {
                            AvatarLoader.makeCircular(this)
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            contentDescription = "发送者头像"
                            visibility = View.GONE
                        },
                    )
                    HLinearLayout(
                        lparams = LayoutParams(
                            width = ViewGroup.LayoutParams.WRAP_CONTENT,
                            height = ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                        init = { orientation = LinearLayout.VERTICAL },
                    ) {
                        metaRow = HLinearLayout(
                            lparams = LayoutParams(
                                width = ViewGroup.LayoutParams.WRAP_CONTENT,
                                height = ViewGroup.LayoutParams.WRAP_CONTENT,
                            ) {
                                leftMargin = dp(parent, 4)
                                rightMargin = dp(parent, 4)
                                bottomMargin = dp(parent, 3)
                            },
                            init = {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                            },
                        ) {
                            nickname = HMaterialTextView(
                                lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT) {
                                    rightMargin = dp(parent, 6)
                                },
                                init = {
                                    TextViewCompat.setTextAppearance(
                                        this,
                                        com.google.android.material.R.style.TextAppearance_Material3_LabelMedium,
                                    )
                                    maxLines = 1
                                    maxWidth = maxBubbleWidth
                                    ellipsize = TextUtils.TruncateAt.END
                                    visibility = View.GONE
                                },
                            )
                            time = HMaterialTextView(
                                lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT),
                                init = {
                                    TextViewCompat.setTextAppearance(
                                        this,
                                        com.google.android.material.R.style.TextAppearance_Material3_LabelSmall,
                                    )
                                    maxLines = 1
                                },
                            )
                        }
                        card = HMaterialCardView(
                            lparams = LayoutParams(
                                width = ViewGroup.LayoutParams.WRAP_CONTENT,
                                height = ViewGroup.LayoutParams.WRAP_CONTENT,
                            ),
                            init = {
                                radius = dp(parent, 12).toFloat()
                                strokeWidth = 0
                                cardElevation = 0f
                                isClickable = true
                                isFocusable = true
                            },
                        ) {
                            HLinearLayout(
                                lparams = LayoutParams(
                                    width = ViewGroup.LayoutParams.WRAP_CONTENT,
                                    height = ViewGroup.LayoutParams.WRAP_CONTENT,
                                ),
                                init = {
                                    orientation = LinearLayout.VERTICAL
                                    setPadding(dp(parent, 12), dp(parent, 8), dp(parent, 12), dp(parent, 8))
                                },
                            ) {
                                image = HShapeableImageView(
                                    lparams = LayoutParams(
                                        width = imageWidth,
                                        height = dp(parent, 180),
                                    ) {
                                        topMargin = dp(parent, 2)
                                        bottomMargin = dp(parent, 2)
                                    },
                                    init = {
                                        scaleType = ImageView.ScaleType.CENTER_CROP
                                        adjustViewBounds = true
                                        visibility = View.GONE
                                        contentDescription = "图片消息"
                                    },
                                )
                                body = HMaterialTextView(
                                    lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT),
                                    init = {
                                        TextViewCompat.setTextAppearance(
                                            this,
                                            com.google.android.material.R.style.TextAppearance_Material3_BodyLarge,
                                        )
                                        maxWidth = maxBubbleWidth
                                        setTextIsSelectable(true)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }.create(parent.context, parent, false)
        return Holder(
            hikage.root,
            rowContainer,
            avatar,
            metaRow,
            nickname,
            time,
            card,
            image,
            body,
            isGroup,
            onImageClick,
            onMessageLongClick,
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val message = getItem(position)
        val previous = if (position > 0) getItem(position - 1) else null
        val firstOfGroup = previous == null ||
            previous.outgoing != message.outgoing ||
            previous.senderUin != message.senderUin ||
            (message.timestampSeconds - previous.timestampSeconds) > GROUP_GAP_SECONDS
        holder.bind(message, firstOfGroup)
    }

    override fun onViewRecycled(holder: Holder) {
        holder.unbind()
        super.onViewRecycled(holder)
    }

    class Holder(
        itemView: android.view.View,
        private val rowContainer: LinearLayout,
        private val avatar: ShapeableImageView,
        private val metaRow: LinearLayout,
        private val nickname: MaterialTextView,
        private val time: MaterialTextView,
        private val card: MaterialCardView,
        private val image: ShapeableImageView,
        private val body: MaterialTextView,
        private val isGroup: Boolean,
        private val onImageClick: (ChatDetailViewModel.UiImage) -> Unit,
        private val onMessageLongClick: (ChatDetailViewModel.UiMessage) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {
        fun bind(message: ChatDetailViewModel.UiMessage, firstOfGroup: Boolean) {
            val context = itemView.context
            val outgoing = message.outgoing
            val edge = if (outgoing) Gravity.END else Gravity.START

            (rowContainer.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = edge
            }.also(rowContainer::setLayoutParams)

            // Tighter top spacing between grouped messages; a wider gap starts a group.
            itemView.setPadding(
                dp(16),
                if (firstOfGroup) dp(8) else dp(1),
                dp(16),
                dp(1),
            )

            // Avatar (group incoming only): shown on the first of a run, otherwise
            // kept INVISIBLE so grouped bubbles stay indented under the first.
            val avatarColumn = isGroup && !outgoing
            when {
                avatarColumn && firstOfGroup -> {
                    avatar.visibility = View.VISIBLE
                    avatar.scaleType = ImageView.ScaleType.CENTER_CROP
                    AvatarLoader.bind(
                        imageView = avatar,
                        localPath = null,
                        urls = AvatarSources.forSenderUin(message.senderUin),
                        fallback = ContextCompat.getDrawable(context, R.drawable.ic_account_circle),
                        circular = true,
                    )
                }

                avatarColumn -> {
                    AvatarLoader.unbind(avatar)
                    avatar.setImageDrawable(null)
                    avatar.visibility = View.INVISIBLE
                }

                else -> {
                    AvatarLoader.unbind(avatar)
                    avatar.setImageDrawable(null)
                    avatar.visibility = View.GONE
                }
            }

            // Meta row (nickname + time) lives OUTSIDE the bubble, above it.  It is
            // shown for the first message of a run, or while a send is in flight.
            val sending = outgoing && message.sendStatus != 0 && message.sendStatus != 2
            val showMeta = firstOfGroup || sending
            metaRow.visibility = if (showMeta) View.VISIBLE else View.GONE
            if (showMeta) {
                metaRow.gravity = Gravity.CENTER_VERTICAL or edge
                alignChild(metaRow, edge)
                alignChild(card, edge)

                val showNickname = isGroup && !outgoing && firstOfGroup && message.senderName.isNotBlank()
                nickname.visibility = if (showNickname) View.VISIBLE else View.GONE
                if (showNickname) nickname.text = message.senderName
                nickname.setTextColor(
                    MaterialColors.getColor(nickname, com.google.android.material.R.attr.colorOnSurfaceVariant),
                )

                time.setTextColor(
                    MaterialColors.getColor(time, com.google.android.material.R.attr.colorOnSurfaceVariant),
                )
                time.text = buildString {
                    val millis = message.timestampSeconds * 1000L
                    if (millis > 0L) append(DateFormat.getTimeFormat(context).format(Date(millis)))
                    if (sending) {
                        if (isNotEmpty()) append("  ·  ")
                        append("发送中")
                    }
                }
            } else {
                alignChild(card, edge)
            }

            card.setCardBackgroundColor(
                MaterialColors.getColor(
                    card,
                    if (outgoing) {
                        com.google.android.material.R.attr.colorPrimaryContainer
                    } else {
                        com.google.android.material.R.attr.colorSurfaceContainerHigh
                    },
                ),
            )
            val onContainer = MaterialColors.getColor(
                card,
                if (outgoing) {
                    com.google.android.material.R.attr.colorOnPrimaryContainer
                } else {
                    com.google.android.material.R.attr.colorOnSurface
                },
            )
            body.setTextColor(onContainer)

            val picture = message.image
            if (picture == null) {
                AvatarLoader.unbind(image)
                image.visibility = View.GONE
                image.setOnClickListener(null)
            } else {
                image.visibility = View.VISIBLE
                val availableLocal = picture.localPaths.firstOrNull(String::isNotBlank)
                AvatarLoader.bind(
                    imageView = image,
                    localPath = availableLocal,
                    urls = picture.remoteUrls,
                    fallback = null,
                    circular = false,
                )
                image.setOnClickListener { onImageClick(picture) }
            }
            body.text = message.text
            body.visibility = if (picture != null && message.text == "[图片]") {
                View.GONE
            } else {
                View.VISIBLE
            }
            card.contentDescription = buildString {
                append(if (outgoing) "我" else message.senderName.ifBlank { "对方" })
                append("：")
                append(message.text)
            }
            card.setOnLongClickListener {
                onMessageLongClick(message)
                true
            }
        }

        fun unbind() {
            AvatarLoader.unbind(image)
            AvatarLoader.unbind(avatar)
            image.setOnClickListener(null)
        }

        private fun alignChild(view: View, edge: Int) {
            (view.layoutParams as? LinearLayout.LayoutParams)?.let { params ->
                if (params.gravity != edge) {
                    params.gravity = edge
                    view.layoutParams = params
                }
            }
        }

        private fun dp(value: Int): Int =
            (value * itemView.resources.displayMetrics.density).toInt()
    }

    private fun dp(parent: ViewGroup, value: Int): Int =
        (value * parent.resources.displayMetrics.density).toInt()

    private companion object {
        const val GROUP_GAP_SECONDS = 300L
        val DIFF = object : DiffUtil.ItemCallback<ChatDetailViewModel.UiMessage>() {
            override fun areItemsTheSame(
                oldItem: ChatDetailViewModel.UiMessage,
                newItem: ChatDetailViewModel.UiMessage,
            ): Boolean = oldItem.stableId == newItem.stableId

            override fun areContentsTheSame(
                oldItem: ChatDetailViewModel.UiMessage,
                newItem: ChatDetailViewModel.UiMessage,
            ): Boolean = oldItem == newItem
        }
    }
}
