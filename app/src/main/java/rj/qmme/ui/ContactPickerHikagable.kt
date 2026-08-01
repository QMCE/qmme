package rj.qmme.ui

import android.annotation.SuppressLint
import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textview.MaterialTextView
import com.highcapable.betterandroid.ui.extension.component.base.getDrawableCompat
import com.highcapable.betterandroid.ui.extension.component.launch
import com.highcapable.hikage.annotation.Hikagable
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.LinearLayout
import com.highcapable.hikage.widget.androidx.recyclerview.widget.RecyclerView
import com.highcapable.hikage.widget.com.google.android.material.appbar.MaterialToolbar
import com.highcapable.hikage.widget.com.google.android.material.imageview.ShapeableImageView
import com.highcapable.hikage.widget.com.google.android.material.textview.MaterialTextView
import androidx.core.widget.TextViewCompat
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import rj.qmme.R
import rj.qmme.viewmodel.ContactsViewModel

/** Contact picker for forwarding messages to a C2C chat. */
class ContactPickerHikagable(
    private val context: Context,
    private val title: String = "选择联系人",
    private val onBack: () -> Unit,
    private val onPick: (chatType: Int, peerUid: String, title: String) -> Unit,
) : HikageScreen {
    private lateinit var recyclerView: RecyclerView
    private val adapter = PickerAdapter()
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
                        this.title = this@ContactPickerHikagable.title
                        navigationIcon = drawableResource(R.drawable.ic_arrow_back)
                        setNavigationContentDescription("返回")
                        setNavigationOnClickListener { onBack() }
                        EdgeToEdgeInsets.applyHorizontalInsets(this)
                    },
                )
                recyclerView = RecyclerView(
                    lparams = LayoutParams(widthMatchParent = true, height = 0) { weight = 1f },
                    init = {
                        layoutManager = LinearLayoutManager(context)
                        adapter = this@ContactPickerHikagable.adapter
                        clipToPadding = false
                        setPadding(dp(8), dp(4), dp(8), dp(8))
                    },
                )
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true, height = 0),
                    init = { EdgeToEdgeInsets.applyBottomInsetSpacer(this) },
                )
            }
        }.also { cachedHikage = it }

    fun bind(owner: LifecycleOwner, contactsViewModel: ContactsViewModel) {
        if (bound) return
        bound = true
        owner.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                contactsViewModel.categories.collectLatest { categories ->
                    adapter.submit(flattenBuddies(categories))
                }
            }
        }
    }

    private fun flattenBuddies(
        categories: List<ContactsViewModel.UiCategory>,
    ): List<ContactsViewModel.UiBuddy> =
        categories.flatMap { it.buddies }

    private inner class PickerAdapter :
        RecyclerView.Adapter<PickerAdapter.Holder>() {
        private var buddies: List<ContactsViewModel.UiBuddy> = emptyList()

        @SuppressLint("NotifyDataSetChanged")
        fun submit(list: List<ContactsViewModel.UiBuddy>) {
            buddies = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            lateinit var avatar: ShapeableImageView
            lateinit var name: MaterialTextView
            lateinit var subtitle: MaterialTextView
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
                            setImageResource(R.drawable.ic_contacts)
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
                        subtitle = MaterialTextView(
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
            return Holder(root, avatar, name, subtitle)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val buddy = buddies[position]
            val displayName = buddy.remark.ifBlank { buddy.nick }
            holder.name.text = displayName
            holder.subtitle.text = buildString {
                if (buddy.nick.isNotBlank() && buddy.nick != buddy.remark) append(buddy.nick)
                if (buddy.uin > 0L) {
                    if (isNotEmpty()) append("  ·  ")
                    append(buddy.uin)
                }
            }.ifBlank { buddy.uid }
            holder.avatar.contentDescription = "$displayName 的头像"
            AvatarLoader.bind(
                imageView = holder.avatar,
                localPath = buddy.avatarPath,
                urls = AvatarSources.forBuddy(buddy),
                fallback = holder.itemView.context.getDrawableCompat(R.drawable.ic_contacts),
            )
            holder.itemView.setOnClickListener {
                onPick(1, buddy.uid.ifBlank { buddy.uin.toString() }, displayName)
            }
        }

        override fun onViewRecycled(holder: Holder) {
            AvatarLoader.unbind(holder.avatar)
            super.onViewRecycled(holder)
        }

        override fun getItemCount(): Int = buddies.size

        inner class Holder(
            itemView: View,
            val avatar: ShapeableImageView,
            val name: MaterialTextView,
            val subtitle: MaterialTextView,
        ) : RecyclerView.ViewHolder(itemView)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
