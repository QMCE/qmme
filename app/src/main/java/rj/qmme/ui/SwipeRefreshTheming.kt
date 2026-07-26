package rj.qmme.ui

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.color.MaterialColors

/**
 * SwipeRefreshLayout predates Material 3 and defaults to a hardcoded black
 * arrow on a white circle, which visibly breaks a dynamic-color surface.
 * Every refresh host in QMME routes through here so the spinner tracks the
 * user's wallpaper palette like the rest of the app.
 */
internal fun applyM3Colors(layout: SwipeRefreshLayout) {
    layout.setColorSchemeColors(
        // colorPrimary is an appcompat-owned attr; with nonTransitiveRClass
        // it is NOT present in the material R class.
        MaterialColors.getColor(
            layout,
            androidx.appcompat.R.attr.colorPrimary,
        ),
        MaterialColors.getColor(
            layout,
            com.google.android.material.R.attr.colorTertiary,
        ),
    )
    layout.setProgressBackgroundColorSchemeColor(
        MaterialColors.getColor(
            layout,
            com.google.android.material.R.attr.colorSurfaceContainerHigh,
        ),
    )
}
