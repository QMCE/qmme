package rj.qmme.ui

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.widget.NestedScrollView
import androidx.core.widget.TextViewCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.textview.MaterialTextView
import com.highcapable.betterandroid.ui.extension.component.base.getDrawableCompat
import com.highcapable.hikage.annotation.Hikagable
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.androidx.core.widget.NestedScrollView
import com.highcapable.hikage.widget.android.widget.LinearLayout
import com.highcapable.hikage.widget.com.google.android.material.appbar.MaterialToolbar
import com.highcapable.hikage.widget.com.google.android.material.button.MaterialButton
import com.highcapable.hikage.widget.com.google.android.material.imageview.ShapeableImageView
import com.highcapable.hikage.widget.com.google.android.material.textview.MaterialTextView
import rj.qmme.R
import rj.qmme.ui.hikage.settingsGroup

/** Contact / friend profile screen for phone. */
class ProfileHikagable(
    private val context: Context,
    private val title: String,
    private val uid: String,
    private val uin: Long,
    private val avatarPath: String,
    private val avatarUrl: String,
    private val subtitle: String,
    private val onBack: () -> Unit,
    private val onOpenChat: () -> Unit,
) : HikageScreen {
    private lateinit var avatarView: ShapeableImageView
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
                        this.title = title
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
                        buildProfileHeader()
                        settingsGroup {
                            row(
                                icon = context.getDrawableCompat(R.drawable.ic_account_circle),
                                title = "QQ 号",
                                subtitle = if (uin > 0L) uin.toString() else "未知",
                            )
                            row(
                                icon = context.getDrawableCompat(R.drawable.ic_info),
                                title = "UID",
                                subtitle = uid.ifBlank { "未知" },
                            )
                        }
                    }
                }
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        setPadding(dp(16), dp(8), dp(16), dp(16))
                        EdgeToEdgeInsets.applyHorizontalInsets(this)
                    },
                ) {
                    MaterialButton(
                        lparams = LayoutParams(widthMatchParent = true),
                        init = {
                            text = "发消息"
                            isAllCaps = false
                            setIconResource(R.drawable.ic_chat)
                            setOnClickListener { onOpenChat() }
                        },
                    )
                }
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true, height = 0),
                    init = { EdgeToEdgeInsets.applyBottomInsetSpacer(this) },
                )
            }
        }.also { cachedHikage = it }

    fun bind() {
        if (!::avatarView.isInitialized) return
        AvatarLoader.bind(
            imageView = avatarView,
            localPath = avatarPath,
            urls = buildList {
                avatarUrl.trim()
                    .takeIf { it.startsWith("http://") || it.startsWith("https://") }
                    ?.let(::add)
                if (uin > 0L) addAll(AvatarSources.forSenderUin(uin))
            },
            fallback = context.getDrawableCompat(R.drawable.ic_account_circle),
        )
    }

    fun dispose() {
        if (::avatarView.isInitialized) AvatarLoader.unbind(avatarView)
    }

    @Hikagable
    private fun Hikage.Performer<LinearLayout.LayoutParams>.buildProfileHeader() {
        LinearLayout(
            lparams = LayoutParams(widthMatchParent = true) {
                topMargin = dp(8)
                bottomMargin = dp(16)
            },
            init = {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(16), dp(16), dp(16), dp(8))
            },
        ) {
            avatarView = ShapeableImageView(
                lparams = LayoutParams(width = dp(96), height = dp(96)),
                init = {
                    setImageResource(R.drawable.ic_account_circle)
                    AvatarLoader.makeCircular(this)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    contentDescription = "$title 的头像"
                },
            )
            MaterialTextView(
                lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT) {
                    topMargin = dp(16)
                },
                init = {
                    text = title
                    TextViewCompat.setTextAppearance(
                        this,
                        com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall_Emphasized,
                    )
                    gravity = Gravity.CENTER
                },
            )
            if (subtitle.isNotBlank()) {
                MaterialTextView(
                    lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT) {
                        topMargin = dp(4)
                    },
                    init = {
                        text = subtitle
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
                        gravity = Gravity.CENTER
                    },
                )
            }
            if (uin > 0L) {
                MaterialTextView(
                    lparams = LayoutParams(width = ViewGroup.LayoutParams.WRAP_CONTENT) {
                        topMargin = dp(2)
                    },
                    init = {
                        text = "QQ $uin"
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
                        gravity = Gravity.CENTER
                    },
                )
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
