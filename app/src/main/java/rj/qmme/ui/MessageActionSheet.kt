package rj.qmme.ui

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.core.widget.TextViewCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors
import com.google.android.material.divider.MaterialDivider
import com.google.android.material.imageview.ShapeableImageView
import com.google.android.material.listitem.ListItemCardView
import com.google.android.material.listitem.ListItemLayout
import com.google.android.material.textview.MaterialTextView
import com.highcapable.betterandroid.ui.extension.component.base.getDrawableCompat

/**
 * Contextual message actions as an M3 bottom sheet of segmented list rows —
 * the Expressive replacement for a centered `AlertDialog.setItems` menu.
 * Destructive rows are error-colored; confirmations stay as dialogs.
 */
internal object MessageActionSheet {

    class Action(
        val label: String,
        @DrawableRes val iconRes: Int,
        val destructive: Boolean = false,
        val run: () -> Unit,
    )

    fun show(context: Context, actions: List<Action>) {
        if (actions.isEmpty()) return
        val dialog = BottomSheetDialog(context)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 20))
        }
        actions.forEachIndexed { index, action ->
            container.addView(
                buildRow(context, action, index, actions.size) {
                    dialog.dismiss()
                    action.run()
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        dialog.setContentView(container)
        dialog.show()
    }

    private fun buildRow(
        context: Context,
        action: Action,
        index: Int,
        total: Int,
        onClick: () -> Unit,
    ): View {
        val listItem = ListItemLayout(context)
        val card = ListItemCardView(
            context,
            null,
            com.google.android.material.R.attr.listItemCardViewSegmentedStyle,
        ).apply {
            setContentPadding(0, 0, 0, 0)
            isSwipeEnabled = false
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val tint = MaterialColors.getColor(
            listItem,
            if (action.destructive) {
                androidx.appcompat.R.attr.colorError
            } else {
                com.google.android.material.R.attr.colorOnSurface
            },
        )

        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val rowLine = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(context, 20), dp(context, 14), dp(context, 20), dp(context, 14))
        }
        rowLine.addView(
            ShapeableImageView(context).apply {
                setImageDrawable(context.getDrawableCompat(action.iconRes))
                imageTintList = ColorStateList.valueOf(tint)
                scaleType = ImageView.ScaleType.FIT_CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(dp(context, 24), dp(context, 24)).apply {
                marginEnd = dp(context, 16)
            },
        )
        rowLine.addView(
            MaterialTextView(context).apply {
                text = action.label
                TextViewCompat.setTextAppearance(
                    this,
                    com.google.android.material.R.style.TextAppearance_Material3_BodyLarge,
                )
                setTextColor(tint)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        content.addView(
            rowLine,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        if (index < total - 1) {
            content.addView(
                MaterialDivider(context).apply {
                    dividerThickness = dp(context, 2)
                    dividerInsetStart = 0
                    dividerInsetEnd = 0
                    dividerColor = MaterialColors.getColor(
                        this,
                        com.google.android.material.R.attr.colorSurfaceContainer,
                    )
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 2)),
            )
        }
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
        listItem.updateAppearance(index, total)
        return listItem
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
