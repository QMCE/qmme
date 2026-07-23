package rj.qmme.ui.hikage

import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.material.listitem.ListItemCardView
import com.google.android.material.listitem.ListItemLayout
import com.highcapable.hikage.annotation.Hikagable
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.attribute.HikageAttribute
import com.highcapable.hikage.core.base.HikagePerformer
import com.highcapable.hikage.core.base.HikageView
import com.highcapable.hikage.core.layout.LayoutParams
import android.view.ViewGroup.LayoutParams as ViewGroupLayoutParams
import android.widget.FrameLayout.LayoutParams as FrameLayoutLayoutParams
import com.highcapable.hikage.core.layout.ViewGroup as HikageViewGroup

/**
 * Local Hikage declarations for the Material 3 list APIs introduced after
 * hikage-widget-material 1.1.1. They intentionally mirror Hikage's generated
 * wrappers so list items stay in the same native View DSL as the rest of QMME.
 */
@Hikagable
inline fun <reified LP : ViewGroupLayoutParams> Hikage.Performer<LP>.ListItemLayout(
    lparams: LayoutParams? = null,
    id: String? = null,
    noinline attrs: HikageAttribute = {},
    noinline init: HikageView<ListItemLayout> = {},
    noinline performer: HikagePerformer<FrameLayoutLayoutParams> = {},
): ListItemLayout = HikageViewGroup(
    viewClass = ListItemLayout::class,
    childLpClass = FrameLayoutLayoutParams::class,
    factory = { context, attributeSet -> ListItemLayout(context, attributeSet) },
    lparams = lparams,
    id = id,
    attrs = attrs,
    init = init,
    performer = performer,
)

@Hikagable
inline fun <reified LP : ViewGroupLayoutParams> Hikage.Performer<LP>.ListItemCardView(
    lparams: LayoutParams? = null,
    id: String? = null,
    noinline attrs: HikageAttribute = {},
    noinline init: HikageView<ListItemCardView> = {},
    noinline performer: HikagePerformer<FrameLayoutLayoutParams> = {},
): ListItemCardView = HikageViewGroup(
    viewClass = ListItemCardView::class,
    childLpClass = FrameLayoutLayoutParams::class,
    factory = { context, attributeSet -> ListItemCardView(context, attributeSet) },
    lparams = lparams,
    id = id,
    attrs = attrs,
    init = init,
    performer = performer,
)

/**
 * The official segmented variant gives ListItemLayout's first/middle/last
 * states an opaque surface. The ordinary ListItemCardView style is deliberately
 * transparent, which is correct for standalone rows but makes a grouped list
 * lose its visual outer container.
 */
@Hikagable
inline fun <reified LP : ViewGroupLayoutParams> Hikage.Performer<LP>.SegmentedListItemCardView(
    lparams: LayoutParams? = null,
    id: String? = null,
    noinline attrs: HikageAttribute = {},
    noinline init: HikageView<ListItemCardView> = {},
    noinline performer: HikagePerformer<FrameLayoutLayoutParams> = {},
): ListItemCardView = HikageViewGroup(
    viewClass = ListItemCardView::class,
    childLpClass = FrameLayoutLayoutParams::class,
    factory = { context, attributeSet ->
        ListItemCardView(
            context,
            attributeSet,
            com.google.android.material.R.attr.listItemCardViewSegmentedStyle,
        )
    },
    lparams = lparams,
    id = id,
    attrs = attrs,
    init = init,
    performer = performer,
)
