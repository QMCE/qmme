package rj.qmme.ui

import com.highcapable.hikage.core.Hikage

/**
 * A screen exposes the complete Hikage delegate instead of wrapping it in an
 * Android View.  The host decides when and where to materialize the delegate
 * (for example, [com.highcapable.hikage.extension.setContentView]).
 */
interface HikageScreen {
    val hikage: Hikage.Delegate<*>
}
