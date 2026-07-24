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

/** Material 3 Expressive message bubbles built entirely with Hikage/native Views. */
class MessageAdapter(
    private val isGroup: Boolean = false,
    private val onImageClick: (ChatDetailViewModel.UiImage) -> Unit = {},
    private val onMessageLongClick: (ChatDetailViewModel.UiMessage) -> Unit = {},
) : ListAdapter<ChatDetailViewModel.UiMessage, MessageAdapter.Holder>(DIFF) {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        lateinit var rowContainer: LinearLayout
        lateinit var avatar: ShapeableImageView
        lateinit var nickname: MaterialTextView
        lateinit var card: MaterialCardView
        lateinit var image: ShapeableImageView
        lateinit var body: MaterialTextView
        lateinit var metadata: MaterialTextView
        val maxBubbleWidth = (parent.resources.displayMetrics.widthPixels * 0.72f).toInt()
        val imageWidth = minOf(dp(parent, 240), maxBubbleWidth)
        val hikage = Hikagable {
            HFrameLayout(
                lparams = LayoutParams(widthMatchParent = true),
                init = {
                    setPadding(dp(parent, 16), dp(parent, 3), dp(parent, 16), dp(parent, 3))
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
                        nickname = HMaterialTextView(
                            lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT) {
                                leftMargin = dp(parent, 4)
                                bottomMargin = dp(parent, 2)
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
                        card = HMaterialCardView(
                            lparams = LayoutParams(
                                width = ViewGroup.LayoutParams.WRAP_CONTENT,
                                height = ViewGroup.LayoutParams.WRAP_CONTENT,
                            ),
                            init = {
                                radius = dp(parent, 24).toFloat()
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
                                    setPadding(dp(parent, 16), dp(parent, 10), dp(parent, 16), dp(parent, 9))
                                },
                            ) {
                                image = HShapeableImageView(
                                    lparams = LayoutParams(
                                        width = imageWidth,
                                        height = dp(parent, 180),
                                    ) {
                                        topMargin = dp(parent, 4)
                                        bottomMargin = dp(parent, 4)
                                    },
                                    init = {
                                        scaleType = ImageView.ScaleType.CENTER_CROP
                                        adjustViewBounds = true
                                        visibility = View.GONE
                                        contentDescription = "图片消息"
                                    },
                                )
                                body = HMaterialTextView(
                                    lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT) {
                                        topMargin = dp(parent, 1)
                                    },
                                    init = {
                                        TextViewCompat.setTextAppearance(
                                            this,
                                            com.google.android.material.R.style.TextAppearance_Material3_BodyLarge,
                                        )
                                        maxWidth = maxBubbleWidth
                                        setTextIsSelectable(true)
                                    },
                                )
                                metadata = HMaterialTextView(
                                    lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT) {
                                        topMargin = dp(parent, 4)
                                    },
                                    init = {
                                        TextViewCompat.setTextAppearance(
                                            this,
                                            com.google.android.material.R.style.TextAppearance_Material3_LabelSmall,
                                        )
                                        maxWidth = maxBubbleWidth
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
            nickname,
            card,
            image,
            body,
            metadata,
            isGroup,
            onImageClick,
            onMessageLongClick,
        )
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun onViewRecycled(holder: Holder) {
        holder.unbind()
        super.onViewRecycled(holder)
    }

    class Holder(
        itemView: android.view.View,
        private val rowContainer: LinearLayout,
        private val avatar: ShapeableImageView,
        private val nickname: MaterialTextView,
        private val card: MaterialCardView,
        private val image: ShapeableImageView,
        private val body: MaterialTextView,
        private val metadata: MaterialTextView,
        private val isGroup: Boolean,
        private val onImageClick: (ChatDetailViewModel.UiImage) -> Unit,
        private val onMessageLongClick: (ChatDetailViewModel.UiMessage) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {
        fun bind(message: ChatDetailViewModel.UiMessage) {
            val context = itemView.context
            (rowContainer.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = if (message.outgoing) Gravity.END else Gravity.START
            }.also(rowContainer::setLayoutParams)

            val showAvatar = isGroup && !message.outgoing
            if (showAvatar) {
                avatar.visibility = View.VISIBLE
                avatar.scaleType = ImageView.ScaleType.CENTER_CROP
                AvatarLoader.bind(
                    imageView = avatar,
                    localPath = null,
                    urls = AvatarSources.forSenderUin(message.senderUin),
                    fallback = ContextCompat.getDrawable(context, R.drawable.ic_account_circle),
                    circular = true,
                )
            } else {
                AvatarLoader.unbind(avatar)
                avatar.setImageDrawable(null)
                avatar.visibility = View.GONE
            }

            val showNickname = isGroup && !message.outgoing && message.senderName.isNotBlank()
            nickname.visibility = if (showNickname) View.VISIBLE else View.GONE
            if (showNickname) nickname.text = message.senderName

            card.setCardBackgroundColor(
                MaterialColors.getColor(
                    card,
                    if (message.outgoing) {
                        com.google.android.material.R.attr.colorPrimaryContainer
                    } else {
                        com.google.android.material.R.attr.colorSurfaceContainerHigh
                    },
                ),
            )
            val onContainer = MaterialColors.getColor(
                card,
                if (message.outgoing) {
                    com.google.android.material.R.attr.colorOnPrimaryContainer
                } else {
                    com.google.android.material.R.attr.colorOnSurface
                },
            )
            body.setTextColor(onContainer)
            metadata.setTextColor(
                MaterialColors.getColor(card, com.google.android.material.R.attr.colorOnSurfaceVariant),
            )
            nickname.setTextColor(
                MaterialColors.getColor(nickname, com.google.android.material.R.attr.colorOnSurfaceVariant),
            )

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
            metadata.text = buildString {
                val millis = message.timestampSeconds * 1000L
                if (millis > 0L) append(DateFormat.getTimeFormat(context).format(Date(millis)))
                if (message.outgoing && message.sendStatus != 0) {
                    if (isNotEmpty()) append("  ·  ")
                    append(if (message.sendStatus == 2) "已发送" else "发送中")
                }
            }
            card.contentDescription = buildString {
                append(if (message.outgoing) "我" else message.senderName.ifBlank { "对方" })
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
    }

    private fun dp(parent: ViewGroup, value: Int): Int =
        (value * parent.resources.displayMetrics.density).toInt()

    private companion object {
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
