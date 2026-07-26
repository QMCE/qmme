package rj.qmme.ui.hikage

import android.content.Context
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.widget.TextViewCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.listitem.ListItemLayout
import com.google.android.material.textview.MaterialTextView
import com.highcapable.hikage.annotation.Hikagable
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.core.layout.ViewGroup as HikageViewGroup
import android.view.ViewGroup.LayoutParams as ViewGroupLayoutParams

/**
 * A statically composed Material 3 segmented list group.
 *
 * The RecyclerView feeds get their first/middle/last shapes from
 * [com.google.android.material.listitem.ListItemViewHolder]; a settings page
 * has a fixed set of rows and no adapter, so this builder calls
 * [ListItemLayout.updateAppearance] directly with the same semantics.
 * Rows are added first, then the group assigns positions once.
 */
class SettingsGroupBuilder internal constructor(private val context: Context) {

    internal val rows = mutableListOf<Row>()

    internal class Row(
        val view: ListItemLayout,
        val divider: MaterialDivider,
    )

    fun row(
        icon: Drawable?,
        title: String,
        subtitle: String? = null,
        destructive: Boolean = false,
        onClick: (() -> Unit)? = null,
    ) {
        val listItem = ListItemLayout(context)
        val card = com.google.android.material.listitem.ListItemCardView(
            context,
            null,
            com.google.android.material.R.attr.listItemCardViewSegmentedStyle,
        ).apply {
            setContentPadding(0, 0, 0, 0)
            isSwipeEnabled = false
            isClickable = onClick != null
            isFocusable = onClick != null
            onClick?.let { action -> setOnClickListener { action() } }
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val rowLine = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(20), dp(14), dp(20), dp(14))
        }

        val contentColor = MaterialColors.getColor(
            listItem,
            if (destructive) {
                androidx.appcompat.R.attr.colorError
            } else {
                com.google.android.material.R.attr.colorOnSurface
            },
        )
        val supportColor = MaterialColors.getColor(
            listItem,
            if (destructive) {
                androidx.appcompat.R.attr.colorError
            } else {
                com.google.android.material.R.attr.colorOnSurfaceVariant
            },
        )

        if (icon != null) {
            val iconView = ShapeableImageView(context).apply {
                setImageDrawable(icon)
                imageTintList = android.content.res.ColorStateList.valueOf(contentColor)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            rowLine.addView(
                iconView,
                LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) },
            )
        }

        val textColumn = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        textColumn.addView(
            MaterialTextView(context).apply {
                text = title
                TextViewCompat.setTextAppearance(
                    this,
                    com.google.android.material.R.style.TextAppearance_Material3_BodyLarge,
                )
                setTextColor(contentColor)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        if (!subtitle.isNullOrBlank()) {
            textColumn.addView(
                MaterialTextView(context).apply {
                    text = subtitle
                    TextViewCompat.setTextAppearance(
                        this,
                        com.google.android.material.R.style.TextAppearance_Material3_BodyMedium,
                    )
                    setTextColor(supportColor)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(2) },
            )
        }
        rowLine.addView(
            textColumn,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        content.addView(
            rowLine,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        // A thin surface-container seam, matching the conversation feed's
        // treatment of a grouped card divided into rows.
        val divider = MaterialDivider(context).apply {
            dividerThickness = dp(2)
            dividerInsetStart = 0
            dividerInsetEnd = 0
            dividerColor = MaterialColors.getColor(
                this,
                com.google.android.material.R.attr.colorSurfaceContainer,
            )
        }
        content.addView(
            divider,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)),
        )

        card.addView(
            content,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        listItem.addView(
            card,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        rows += Row(listItem, divider)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}

/**
 * Declares a segmented settings group inside a Hikage tree.
 *
 * Rows are plain Views rather than DSL nodes because their first/middle/last
 * shape depends on the finished group size; they are attached to a DSL-owned
 * container once the whole group is known.
 */
@Hikagable
fun <LP : ViewGroupLayoutParams> Hikage.Performer<LP>.settingsGroup(
    lparams: LayoutParams? = null,
    body: SettingsGroupBuilder.() -> Unit,
): LinearLayout = HikageViewGroup(
    viewClass = LinearLayout::class,
    factory = { context, _ -> LinearLayout(context) },
    lparams = lparams ?: LayoutParams(widthMatchParent = true),
    init = {
        orientation = LinearLayout.VERTICAL
        val builder = SettingsGroupBuilder(context).apply(body)
        val total = builder.rows.size
        builder.rows.forEachIndexed { index, row ->
            // Same call the official ListItemViewHolder makes; it resolves the
            // listItemShapeAppearance{First,Middle,Last,Single} theme attrs.
            row.view.updateAppearance(index, total)
            row.divider.visibility = if (index < total - 1) View.VISIBLE else View.GONE
            addView(
                row.view,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    },
)
