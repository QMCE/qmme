package rj.qmme.ui.hikage

import com.google.android.material.button.MaterialButton
import com.highcapable.hikage.annotation.Hikagable
import com.highcapable.hikage.core.Hikage
import com.highcapable.hikage.core.attribute.HikageAttribute
import com.highcapable.hikage.core.base.HikageView
import com.highcapable.hikage.core.layout.LayoutParams
import com.highcapable.hikage.core.layout.View as HikageViewOf
import android.view.ViewGroup.LayoutParams as ViewGroupLayoutParams

/**
 * Material 3 Expressive button constructors.
 *
 * Constructing a [MaterialButton] with the theme's icon-button *defStyleAttr*
 * is not cosmetic: `Widget.Material3Expressive.Button.IconButton.*` points
 * `shapeAppearance` at `@xml/m3expressive_button_shape_state_list`, which is
 * what produces the M3 Expressive pressed shape morph (round -> squircle) and
 * the correct 40/48dp touch target. Squashing an ordinary MaterialButton with
 * `text = ""`, `minWidth = 0` and a transparent tint reproduces neither.
 */

/** Standard (transparent) icon button — low emphasis, e.g. composer actions. */
@Hikagable
inline fun <reified LP : ViewGroupLayoutParams> Hikage.Performer<LP>.IconButton(
    lparams: LayoutParams? = null,
    id: String? = null,
    noinline attrs: HikageAttribute = {},
    noinline init: HikageView<MaterialButton> = {},
): MaterialButton = HikageViewOf(
    viewClass = MaterialButton::class,
    factory = { context, attributeSet ->
        MaterialButton(
            context,
            attributeSet,
            com.google.android.material.R.attr.materialIconButtonStyle,
        )
    },
    lparams = lparams,
    id = id,
    attrs = attrs,
    init = init,
)

/** Filled icon button — high emphasis, e.g. the send action. */
@Hikagable
inline fun <reified LP : ViewGroupLayoutParams> Hikage.Performer<LP>.FilledIconButton(
    lparams: LayoutParams? = null,
    id: String? = null,
    noinline attrs: HikageAttribute = {},
    noinline init: HikageView<MaterialButton> = {},
): MaterialButton = HikageViewOf(
    viewClass = MaterialButton::class,
    factory = { context, attributeSet ->
        MaterialButton(
            context,
            attributeSet,
            com.google.android.material.R.attr.materialIconButtonFilledStyle,
        )
    },
    lparams = lparams,
    id = id,
    attrs = attrs,
    init = init,
)

/** Tonal icon button — medium emphasis, e.g. the attachment sheet entries. */
@Hikagable
inline fun <reified LP : ViewGroupLayoutParams> Hikage.Performer<LP>.TonalIconButton(
    lparams: LayoutParams? = null,
    id: String? = null,
    noinline attrs: HikageAttribute = {},
    noinline init: HikageView<MaterialButton> = {},
): MaterialButton = HikageViewOf(
    viewClass = MaterialButton::class,
    factory = { context, attributeSet ->
        MaterialButton(
            context,
            attributeSet,
            com.google.android.material.R.attr.materialIconButtonFilledTonalStyle,
        )
    },
    lparams = lparams,
    id = id,
    attrs = attrs,
    init = init,
)

/** Tonal (filled-tonal) text button — the M3 Expressive default for secondary actions. */
@Hikagable
inline fun <reified LP : ViewGroupLayoutParams> Hikage.Performer<LP>.TonalButton(
    lparams: LayoutParams? = null,
    id: String? = null,
    noinline attrs: HikageAttribute = {},
    noinline init: HikageView<MaterialButton> = {},
): MaterialButton = HikageViewOf(
    viewClass = MaterialButton::class,
    factory = { context, attributeSet ->
        MaterialButton(
            context,
            attributeSet,
            com.google.android.material.R.attr.materialButtonTonalStyle,
        )
    },
    lparams = lparams,
    id = id,
    attrs = attrs,
    init = init,
)

/** Outlined button — required style for MaterialButtonToggleGroup children. */
@Hikagable
inline fun <reified LP : ViewGroupLayoutParams> Hikage.Performer<LP>.OutlinedButton(
    lparams: LayoutParams? = null,
    id: String? = null,
    noinline attrs: HikageAttribute = {},
    noinline init: HikageView<MaterialButton> = {},
): MaterialButton = HikageViewOf(
    viewClass = MaterialButton::class,
    factory = { context, attributeSet ->
        MaterialButton(
            context,
            attributeSet,
            com.google.android.material.R.attr.materialButtonOutlinedStyle,
        )
    },
    lparams = lparams,
    id = id,
    attrs = attrs,
    init = init,
)

/** Text button — lowest emphasis, e.g. inline "retry" affordances. */
@Hikagable
inline fun <reified LP : ViewGroupLayoutParams> Hikage.Performer<LP>.TextButton(
    lparams: LayoutParams? = null,
    id: String? = null,
    noinline attrs: HikageAttribute = {},
    noinline init: HikageView<MaterialButton> = {},
): MaterialButton = HikageViewOf(
    viewClass = MaterialButton::class,
    factory = { context, attributeSet ->
        MaterialButton(
            context,
            attributeSet,
            // appcompat-owned attr; not in the material R class under
            // nonTransitiveRClass.
            androidx.appcompat.R.attr.borderlessButtonStyle,
        )
    },
    lparams = lparams,
    id = id,
    attrs = attrs,
    init = init,
)
