package rj.qmme.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.textview.MaterialTextView
import com.highcapable.hikage.annotation.Hikagable
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.base.Hikagable
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.widget.android.widget.LinearLayout
import com.highcapable.hikage.widget.com.google.android.material.appbar.MaterialToolbar
import com.highcapable.hikage.widget.com.google.android.material.button.MaterialButton
import com.highcapable.hikage.widget.com.google.android.material.imageview.ShapeableImageView
import com.highcapable.hikage.widget.com.google.android.material.textview.MaterialTextView
import androidx.core.widget.TextViewCompat
import rj.qmme.R
import rj.qmme.ui.hikage.OutlinedButton

/** Full-screen confirmation for logout / force exit and similar actions. */
class ConfirmActionHikagable(
    private val context: Context,
    private val title: String,
    private val message: String,
    private val confirmLabel: String,
    private val destructive: Boolean = true,
    private val onBack: () -> Unit,
    private val onConfirm: () -> Unit,
) : HikageScreen {
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
                        navigationIcon = drawableResource(R.drawable.ic_arrow_back)
                        setNavigationContentDescription("返回")
                        setNavigationOnClickListener { onBack() }
                        EdgeToEdgeInsets.applyHorizontalInsets(this)
                    },
                )
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true, height = 0) { weight = 1f },
                    init = {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        setPadding(dp(24), dp(16), dp(24), dp(16))
                        EdgeToEdgeInsets.applyHorizontalInsets(this)
                    },
                ) {
                    ShapeableImageView(
                        lparams = LayoutParams(width = dp(72), height = dp(72)),
                        init = {
                            setImageResource(R.drawable.ic_warning)
                            imageTintList = ColorStateList.valueOf(
                                MaterialColors.getColor(
                                    this,
                                    if (destructive) {
                                        androidx.appcompat.R.attr.colorError
                                    } else {
                                        androidx.appcompat.R.attr.colorPrimary
                                    },
                                ),
                            )
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            contentDescription = null
                            importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        },
                    )
                    MaterialTextView(
                        lparams = LayoutParams(widthMatchParent = true) {
                            topMargin = dp(24)
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
                    MaterialTextView(
                        lparams = LayoutParams(widthMatchParent = true) {
                            topMargin = dp(12)
                        },
                        init = {
                            text = message
                            TextViewCompat.setTextAppearance(
                                this,
                                com.google.android.material.R.style.TextAppearance_Material3_BodyLarge,
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
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true),
                    init = {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        setPadding(dp(16), dp(8), dp(16), dp(16))
                        EdgeToEdgeInsets.applyHorizontalInsets(this)
                    },
                ) {
                    OutlinedButton(
                        lparams = LayoutParams(width = 0, height = ViewGroup.LayoutParams.WRAP_CONTENT) {
                            weight = 1f
                            marginEnd = dp(8)
                        },
                        init = {
                            text = "取消"
                            isAllCaps = false
                            setOnClickListener { onBack() }
                        },
                    )
                    MaterialButton(
                        lparams = LayoutParams(width = 0, height = ViewGroup.LayoutParams.WRAP_CONTENT) {
                            weight = 1f
                            marginStart = dp(8)
                        },
                        init = {
                            text = confirmLabel
                            isAllCaps = false
                            if (destructive) {
                                backgroundTintList = ColorStateList.valueOf(
                                    MaterialColors.getColor(
                                        this,
                                        androidx.appcompat.R.attr.colorError,
                                    ),
                                )
                                setTextColor(
                                    MaterialColors.getColor(
                                        this,
                                        com.google.android.material.R.attr.colorOnError,
                                    ),
                                )
                            }
                            setOnClickListener { onConfirm() }
                        },
                    )
                }
                LinearLayout(
                    lparams = LayoutParams(widthMatchParent = true, height = 0),
                    init = { EdgeToEdgeInsets.applyBottomInsetSpacer(this) },
                )
            }
        }.also { cachedHikage = it }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
