package rj.qmme.ui.hikage

import com.google.android.material.loadingindicator.LoadingIndicator
import com.highcapable.hikage.annotation.Hikagable
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.attribute.HikageAttribute
import com.highcapable.hikage.core.base.HikageView
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.core.layout.View as HikageViewOf
import android.view.ViewGroup.LayoutParams as ViewGroupLayoutParams

/**
 * Hikage declaration for the M3 Expressive [LoadingIndicator] — the
 * shape-morphing loader that replaces indeterminate circular spinners for
 * "contained" waits like the QR bootstrap. hikage-widget-material 1.1.1
 * predates the component, so QMME declares it locally, exactly like the
 * listitem package in [Material3ListItemHikage].
 */
@Hikagable
inline fun <reified LP : ViewGroupLayoutParams> Hikage.Performer<LP>.LoadingIndicator(
    lparams: LayoutParams? = null,
    id: String? = null,
    noinline attrs: HikageAttribute = {},
    noinline init: HikageView<LoadingIndicator> = {},
): LoadingIndicator = HikageViewOf(
    viewClass = LoadingIndicator::class,
    factory = { context, attributeSet -> LoadingIndicator(context, attributeSet) },
    lparams = lparams,
    id = id,
    attrs = attrs,
    init = init,
)
