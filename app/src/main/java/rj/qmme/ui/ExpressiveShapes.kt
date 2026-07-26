package rj.qmme.ui

import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import com.google.android.material.color.MaterialColors
import com.google.android.material.shape.MaterialShapes

/**
 * M3 Expressive decorative shapes for empty states.
 *
 * The official empty-state recipe places the glyph on an expressive shape
 * (cookie / clover / flower) filled with a container tone instead of showing
 * a lone half-transparent icon. [MaterialShapes] ships 35 normalized
 * RoundedPolygons and a drawable factory, so no path math lives here.
 */
internal object ExpressiveShapes {

    /**
     * A cookie-12 badge tinted secondaryContainer, sized by the view bounds.
     * The [inset] drawable centers the glyph at roughly half the badge size,
     * which matches the official empty-state proportions.
     */
    fun emptyStateBadge(anchor: View, glyph: Drawable?, sizePx: Int): Drawable {
        val badge = MaterialShapes.createShapeDrawable(MaterialShapes.COOKIE_12).apply {
            intrinsicWidth = sizePx
            intrinsicHeight = sizePx
            paint.color = MaterialColors.getColor(
                anchor,
                com.google.android.material.R.attr.colorSecondaryContainer,
            )
        }
        if (glyph == null) return badge
        val tinted = glyph.mutate().apply {
            setTint(
                MaterialColors.getColor(
                    anchor,
                    com.google.android.material.R.attr.colorOnSecondaryContainer,
                ),
            )
        }
        val inset = sizePx / 4
        return LayerDrawable(arrayOf(badge, tinted)).apply {
            setLayerInset(1, inset, inset, inset, inset)
        }
    }
}
