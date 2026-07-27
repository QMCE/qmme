package rj.qmme.ui

import android.text.TextUtils
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.widget.TextViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.shape.ShapeAppearanceModel
import com.google.android.material.textview.MaterialTextView
import com.highcapable.betterandroid.ui.extension.component.base.getDrawableCompat
import com.highcapable.betterandroid.ui.extension.view.textColor
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.FrameLayout
import com.highcapable.hikage.widget.android.widget.LinearLayout
import com.highcapable.hikage.widget.com.google.android.material.card.MaterialCardView
import com.highcapable.hikage.widget.com.google.android.material.imageview.ShapeableImageView
import com.highcapable.hikage.widget.com.google.android.material.textview.MaterialTextView
import rj.qmme.R
import rj.qmme.viewmodel.ChatDetailViewModel
import java.util.Calendar
import java.util.Date

/** Material 3 Expressive message rows: grouped by sender, list-flavored, time on the meta row. */
class MessageAdapter(
    private val isGroup: Boolean = false,
    private val onImageClick: (ChatDetailViewModel.UiImage, View?) -> Unit = { _, _ -> },
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
            FrameLayout(
                lparams = LayoutParams(widthMatchParent = true),
                init = {
                    setPadding(dp(parent, 16), dp(parent, 1), dp(parent, 16), dp(parent, 1))
                },
            ) {
                rowContainer = LinearLayout(
                    lparams = LayoutParams(
                        width = ViewGroup.LayoutParams.WRAP_CONTENT,
                        height = ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                    init = {
                        orientation = LinearLayout.HORIZONTAL
                    },
                ) {
                    avatar = ShapeableImageView(
                        lparams = LayoutParams(width = dp(parent, 36), height = dp(parent, 36)) {
                            gravity = Gravity.TOP
                            marginEnd = dp(parent, 8)
                        },
                        init = {
                            AvatarLoader.makeCircular(this)
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            contentDescription = "发送者头像"
                            visibility = View.GONE
                        },
                    )
                    LinearLayout(
                        lparams = LayoutParams(
                            width = ViewGroup.LayoutParams.WRAP_CONTENT,
                            height = ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                        init = { orientation = LinearLayout.VERTICAL },
                    ) {
                        metaRow = LinearLayout(
                            lparams = LayoutParams(
                                width = ViewGroup.LayoutParams.WRAP_CONTENT,
                                height = ViewGroup.LayoutParams.WRAP_CONTENT,
                            ) {
                                marginStart = dp(parent, 4)
                                marginEnd = dp(parent, 4)
                                bottomMargin = dp(parent, 3)
                            },
                            init = {
                                orientation = LinearLayout.HORIZONTAL
                                gravity = Gravity.CENTER_VERTICAL
                            },
                        ) {
                            nickname = MaterialTextView(
                                lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT) {
                                    marginEnd = dp(parent, 6)
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
                            time = MaterialTextView(
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
                        card = MaterialCardView(
                            lparams = LayoutParams(
                                width = ViewGroup.LayoutParams.WRAP_CONTENT,
                                height = ViewGroup.LayoutParams.WRAP_CONTENT,
                            ),
                            init = {
                                // Corners are grouped-state dependent and are
                                // assigned per bind() — see applyGroupedShape.
                                strokeWidth = 0
                                cardElevation = 0f
                                isClickable = true
                                isFocusable = true
                            },
                        ) {
                            LinearLayout(
                                lparams = LayoutParams(
                                    width = ViewGroup.LayoutParams.WRAP_CONTENT,
                                    height = ViewGroup.LayoutParams.WRAP_CONTENT,
                                ),
                                init = {
                                    orientation = LinearLayout.VERTICAL
                                    setPadding(
                                        dp(parent, 12),
                                        dp(parent, 8),
                                        dp(parent, 12),
                                        dp(parent, 8)
                                    )
                                },
                            ) {
                                image = ShapeableImageView(
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
                                body = MaterialTextView(
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
        val next = if (position < itemCount - 1) getItem(position + 1) else null
        val firstOfGroup = previous == null || !sameRun(previous, message)
        val lastOfGroup = next == null || !sameRun(message, next)
        holder.bind(message, firstOfGroup, lastOfGroup)
    }

    private fun sameRun(
        earlier: ChatDetailViewModel.UiMessage,
        later: ChatDetailViewModel.UiMessage,
    ): Boolean = earlier.outgoing == later.outgoing &&
            earlier.senderUin == later.senderUin &&
            (later.timestampSeconds - earlier.timestampSeconds) <= GROUP_GAP_SECONDS

    override fun onCurrentListChanged(
        previousList: MutableList<ChatDetailViewModel.UiMessage>,
        currentList: MutableList<ChatDetailViewModel.UiMessage>,
    ) {
        // Grouped corners depend on neighbours, so the rows adjacent to an
        // insertion boundary must re-resolve even though their own content is
        // unchanged (DiffUtil would otherwise skip them).
        if (previousList.isEmpty() || currentList.size <= previousList.size) return
        val appendBoundary = previousList.size - 1
        if (appendBoundary in currentList.indices &&
            currentList.getOrNull(appendBoundary)?.stableId ==
            previousList.lastOrNull()?.stableId
        ) {
            // New messages arrived at the tail: the old tail may no longer be
            // the last of its run.
            notifyItemChanged(appendBoundary)
        } else {
            // Older history was prepended: the old head may no longer be the
            // first of its run.
            val prependCount = currentList.size - previousList.size
            if (prependCount in currentList.indices) notifyItemChanged(prependCount)
        }
    }

    override fun onViewRecycled(holder: Holder) {
        holder.unbind()
        super.onViewRecycled(holder)
    }

    class Holder(
        itemView: View,
        private val rowContainer: LinearLayout,
        private val avatar: ShapeableImageView,
        private val metaRow: LinearLayout,
        private val nickname: MaterialTextView,
        private val time: MaterialTextView,
        private val card: MaterialCardView,
        private val image: ShapeableImageView,
        private val body: MaterialTextView,
        private val isGroup: Boolean,
        private val onImageClick: (ChatDetailViewModel.UiImage, View?) -> Unit,
        private val onMessageLongClick: (ChatDetailViewModel.UiMessage) -> Unit,
    ) : RecyclerView.ViewHolder(itemView) {
        fun bind(
            message: ChatDetailViewModel.UiMessage,
            firstOfGroup: Boolean,
            lastOfGroup: Boolean,
        ) {
            val context = itemView.context
            val outgoing = message.outgoing
            val edge = if (outgoing) Gravity.END else Gravity.START
            applyGroupedShape(outgoing, firstOfGroup, lastOfGroup)

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
                        fallback = context.getDrawableCompat(R.drawable.ic_account_circle),
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

                val showNickname =
                    isGroup && !outgoing && firstOfGroup && message.senderName.isNotBlank()
                nickname.visibility = if (showNickname) View.VISIBLE else View.GONE
                if (showNickname) nickname.text = message.senderName
                nickname.textColor = MaterialColors.getColor(
                    nickname,
                    com.google.android.material.R.attr.colorOnSurfaceVariant
                )

                time.textColor = MaterialColors.getColor(
                    time,
                    com.google.android.material.R.attr.colorOnSurfaceVariant
                )
                time.text = buildString {
                    val millis = message.timestampSeconds * 1000L
                    // A freshly loaded history spans days, and a bare clock time
                    // on yesterday's messages reads as today's. Same convention
                    // as the conversation feed: 昨天/weekday/date prefix.
                    if (millis > 0L) append(formatMessageTime(context, millis))
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
            body.textColor = onContainer

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
                // bind() resets the shape to sharp rectangles synchronously
                // before returning, so this override always lands after it. A
                // hard-cornered photo inside an 18dp-radius bubble looks pasted
                // on; the small M3 corner keeps it part of the bubble.
                image.shapeAppearanceModel = ShapeAppearanceModel.builder()
                    .setAllCornerSizes(dp(8).toFloat())
                    .build()
                image.setOnClickListener { onImageClick(picture, image) }
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

        /**
         * M3 Expressive grouped-bubble corners (the Google Messages pattern):
         * the outer corners of a run stay large while the corners facing a
         * same-sender neighbour tighten, so a run reads as one thought.
         */
        private fun applyGroupedShape(
            outgoing: Boolean,
            firstOfGroup: Boolean,
            lastOfGroup: Boolean,
        ) {
            val large = dp(18).toFloat()
            val tight = dp(4).toFloat()
            val top = if (firstOfGroup) large else tight
            val bottom = if (lastOfGroup) large else tight
            // The tightened corners sit on the sender's side of the bubble.
            val builder = ShapeAppearanceModel.builder()
            if (outgoing) {
                builder
                    .setTopLeftCornerSize(large)
                    .setBottomLeftCornerSize(large)
                    .setTopRightCornerSize(top)
                    .setBottomRightCornerSize(bottom)
            } else {
                builder
                    .setTopRightCornerSize(large)
                    .setBottomRightCornerSize(large)
                    .setTopLeftCornerSize(top)
                    .setBottomLeftCornerSize(bottom)
            }
            card.shapeAppearanceModel = builder.build()
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
        const val MILLIS_PER_DAY = 86_400_000L
        val WEEKDAYS = arrayOf("周日", "周一", "周二", "周三", "周四", "周五", "周六")

        /** Clock time for today; 昨天/weekday/date-prefixed clock time otherwise. */
        fun formatMessageTime(context: android.content.Context, millis: Long): String {
            val clock = DateFormat.getTimeFormat(context).format(Date(millis))
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
                dayDiff <= 0 -> clock
                dayDiff == 1 -> "昨天 $clock"
                dayDiff < 7 -> "${WEEKDAYS[then.get(Calendar.DAY_OF_WEEK) - 1]} $clock"
                then.get(Calendar.YEAR) == now.get(Calendar.YEAR) ->
                    "${then.get(Calendar.MONTH) + 1}月${then.get(Calendar.DAY_OF_MONTH)}日 $clock"
                else ->
                    "${then.get(Calendar.YEAR)}/${then.get(Calendar.MONTH) + 1}/" +
                        "${then.get(Calendar.DAY_OF_MONTH)} $clock"
            }
        }

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
