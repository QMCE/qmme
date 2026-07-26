package rj.qmme.ui.hikage

import com.google.android.material.appbar.CollapsingToolbarLayout
import com.highcapable.hikage.annotation.Hikagable
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.attribute.HikageAttribute
import com.highcapable.hikage.core.base.HikagePerformer
import com.highcapable.hikage.core.base.HikageView
import com.highcapable.hikage.core.layout.LayoutParams
import android.view.ViewGroup.LayoutParams as ViewGroupLayoutParams
import com.highcapable.hikage.core.layout.ViewGroup as HikageViewGroup

/**
 * Hikage's generated CollapsingToolbarLayout wrapper always uses the default
 * `collapsingToolbarLayoutStyle` (the *small* app bar). The M3 Expressive
 * flexible medium app bar (112dp expanded, large title + subtitle) lives on a
 * separate defStyleAttr, so QMME declares a constructor-styled variant here —
 * the same local-wrapper pattern as [ListItemLayout] in
 * [Material3ListItemHikage].
 */
@Suppress("FunctionName")
@Hikagable
inline fun <reified LP : ViewGroupLayoutParams> Hikage.Performer<LP>.MediumCollapsingToolbar(
    lparams: LayoutParams? = null,
    id: String? = null,
    noinline attrs: HikageAttribute = {},
    noinline init: HikageView<CollapsingToolbarLayout> = {},
    noinline performer: HikagePerformer<CollapsingToolbarLayout.LayoutParams> = {},
): CollapsingToolbarLayout = HikageViewGroup(
    viewClass = CollapsingToolbarLayout::class,
    childLpClass = CollapsingToolbarLayout.LayoutParams::class,
    factory = { context, attributeSet ->
        CollapsingToolbarLayout(
            context,
            attributeSet,
            com.google.android.material.R.attr.collapsingToolbarLayoutMediumStyle,
        )
    },
    lparams = lparams,
    id = id,
    attrs = attrs,
    init = init,
    performer = performer,
)
