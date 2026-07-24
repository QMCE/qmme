package rj.qmme.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Runtime inset hooks used directly from Hikage view [init] blocks.
 *
 * The screens remain one Hikage tree; this only supplies the system-bar values
 * that cannot be known until Android attaches the native View hierarchy.
 */
internal object EdgeToEdgeInsets {
    private data class Padding(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    /** Applies the full safe area to a scrollable, standalone screen root. */
    fun applyContentInsets(content: View) {
        val initial = content.padding()
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, windowInsets ->
            val safe = windowInsets.safeArea()
            view.setPadding(
                initial.left + safe.left,
                initial.top + safe.top,
                initial.right + safe.right,
                initial.bottom + safe.bottom,
            )
            windowInsets
        }
        content.requestInsetsWhenAttached()
    }

    /** Keeps a full-width native component clear of left/right cutouts. */
    fun applyHorizontalInsets(content: View) {
        val initial = content.padding()
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, windowInsets ->
            val safe = windowInsets.safeArea()
            view.setPadding(
                initial.left + safe.left,
                initial.top,
                initial.right + safe.right,
                initial.bottom,
            )
            windowInsets
        }
        content.requestInsetsWhenAttached()
    }

    /** Sizes a Hikage spacer to the status-bar/cutout height. */
    fun applyTopInsetSpacer(spacer: View) {
        ViewCompat.setOnApplyWindowInsetsListener(spacer) { view, windowInsets ->
            view.setHeight(windowInsets.safeArea().top)
            windowInsets
        }
        spacer.requestInsetsWhenAttached()
    }

    /** Sizes a Hikage spacer to the navigation/gesture-bar height. */
    fun applyBottomInsetSpacer(spacer: View) {
        ViewCompat.setOnApplyWindowInsetsListener(spacer) { view, windowInsets ->
            view.setHeight(windowInsets.safeArea().bottom)
            windowInsets
        }
        spacer.requestInsetsWhenAttached()
    }

    /**
     * Like [applyBottomInsetSpacer], but also grows to the IME height while the
     * soft keyboard is open, so a composer sitting above this spacer lifts above
     * the keyboard instead of being covered (edge-to-edge doesn't auto-resize).
     */
    fun applyBottomInsetSpacerWithIme(spacer: View) {
        ViewCompat.setOnApplyWindowInsetsListener(spacer) { view, windowInsets ->
            val bottom = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout() or
                    WindowInsetsCompat.Type.ime(),
            ).bottom
            view.setHeight(bottom)
            windowInsets
        }
        spacer.requestInsetsWhenAttached()
    }

    private fun WindowInsetsCompat.safeArea() =
        getInsets(
            WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout(),
        )

    private fun View.padding() = Padding(
        left = paddingLeft,
        top = paddingTop,
        right = paddingRight,
        bottom = paddingBottom,
    )

    private fun View.setHeight(height: Int) {
        val params = layoutParams ?: return
        if (params.height != height) {
            params.height = height
            layoutParams = params
        }
    }

    private fun View.requestInsetsWhenAttached() {
        if (isAttachedToWindow) {
            ViewCompat.requestApplyInsets(this)
            return
        }
        addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                view.removeOnAttachStateChangeListener(this)
                ViewCompat.requestApplyInsets(view)
            }

            override fun onViewDetachedFromWindow(view: View) = Unit
        })
    }
}
