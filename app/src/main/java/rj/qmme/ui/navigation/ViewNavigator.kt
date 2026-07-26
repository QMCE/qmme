package rj.qmme.ui.navigation

import android.view.View
import android.widget.FrameLayout
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.transition.Transition
import androidx.transition.TransitionManager
import com.google.android.material.transition.MaterialSharedAxis

/**
 * Native-View navigation stack for QMME's Hikage screens.
 *
 * Every entry has its own LifecycleOwner. Hidden or removed pages stop their
 * StateFlow collectors even though the hosting Activity stays alive.
 *
 * Motion: pushes and pops run M3 shared-axis X transitions by default; both
 * accept an override so callers can use e.g. a container transform for
 * image previews. The predictive back gesture scales and shifts the top
 * page with the user's finger per the M3 predictive-back spec.
 */
class ViewNavigator(
    activity: AppCompatActivity,
    private val host: FrameLayout,
    private val onRootBack: () -> Unit = { activity.finish() },
) : DefaultLifecycleObserver {
    class Entry(
        val route: String,
        val view: View,
        private val disposeAction: () -> Unit = {},
    ) {
        val lifecycleOwner = ScreenLifecycleOwner()

        internal fun show(hostStarted: Boolean) {
            view.visibility = View.VISIBLE
            if (hostStarted) lifecycleOwner.start() else lifecycleOwner.create()
        }

        internal fun hide() {
            lifecycleOwner.stop()
            view.visibility = View.GONE
        }

        internal fun dispose() {
            lifecycleOwner.destroy()
            disposeAction()
        }
    }

    class ScreenLifecycleOwner internal constructor() : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        private var destroyed = false

        override val lifecycle: Lifecycle
            get() = registry

        internal fun create() {
            if (!destroyed && registry.currentState == Lifecycle.State.INITIALIZED) {
                registry.currentState = Lifecycle.State.CREATED
            }
        }

        internal fun start() {
            if (destroyed) return
            create()
            registry.currentState = Lifecycle.State.STARTED
        }

        internal fun stop() {
            if (!destroyed && registry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                registry.currentState = Lifecycle.State.CREATED
            }
        }

        internal fun destroy() {
            if (destroyed) return
            destroyed = true
            registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private val entries = ArrayDeque<Entry>()
    private var hostStarted = activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    /** The page currently tracking the predictive back gesture. */
    private var predictiveTarget: View? = null

    init {
        activity.lifecycle.addObserver(this)
        activity.onBackPressedDispatcher.addCallback(
            activity,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackStarted(backEvent: BackEventCompat) {
                    predictiveTarget = if (entries.size > 1) entries.lastOrNull()?.view else null
                }

                override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                    val view = predictiveTarget ?: return
                    // M3 predictive back: the surface shrinks toward ~90% and
                    // follows the finger horizontally, away from the swiped edge.
                    val progress = backEvent.progress
                    val scale = 1f - MAX_SCALE_DELTA * progress
                    view.scaleX = scale
                    view.scaleY = scale
                    val direction =
                        if (backEvent.swipeEdge == BackEventCompat.EDGE_LEFT) 1f else -1f
                    view.translationX =
                        direction * progress * view.width * MAX_SHIFT_FRACTION
                }

                override fun handleOnBackCancelled() {
                    predictiveTarget?.let(::resetTransforms)
                    predictiveTarget = null
                }

                override fun handleOnBackPressed() {
                    predictiveTarget?.let(::resetTransforms)
                    predictiveTarget = null
                    if (!pop()) onRootBack()
                }
            },
        )
    }

    val currentRoute: String?
        get() = entries.lastOrNull()?.route

    fun replaceRoot(entry: Entry) {
        clear()
        attach(entry)
        entries.addLast(entry)
        entry.show(hostStarted)
    }

    fun push(entry: Entry, transition: Transition? = null) {
        // Default M3 lateral navigation: shared X axis, forward direction.
        // TransitionManager picks up the visibility flips that follow.
        TransitionManager.beginDelayedTransition(
            host,
            transition ?: MaterialSharedAxis(MaterialSharedAxis.X, true),
        )
        entries.lastOrNull()?.hide()
        attach(entry)
        entries.addLast(entry)
        entry.show(hostStarted)
    }

    fun pop(transition: Transition? = null): Boolean {
        if (entries.size <= 1) return false
        TransitionManager.beginDelayedTransition(
            host,
            transition ?: MaterialSharedAxis(MaterialSharedAxis.X, false),
        )
        val removed = entries.removeLast()
        host.removeView(removed.view)
        removed.dispose()
        entries.lastOrNull()?.show(hostStarted)
        return true
    }

    fun popToRoot() {
        while (entries.size > 1) pop()
    }

    fun clear() {
        while (entries.isNotEmpty()) entries.removeLast().dispose()
        host.removeAllViews()
    }

    override fun onStart(owner: LifecycleOwner) {
        hostStarted = true
        entries.lastOrNull()?.show(hostStarted = true)
    }

    override fun onStop(owner: LifecycleOwner) {
        hostStarted = false
        entries.lastOrNull()?.hide()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        clear()
        owner.lifecycle.removeObserver(this)
    }

    private fun attach(entry: Entry) {
        host.addView(
            entry.view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun resetTransforms(view: View) {
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
    }

    private companion object {
        const val MAX_SCALE_DELTA = 0.10f
        const val MAX_SHIFT_FRACTION = 0.05f
    }
}
