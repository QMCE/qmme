package rj.qmme.ui.navigation

import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry

/**
 * Native-View navigation stack for QMME's Hikage screens.
 *
 * Every entry has its own LifecycleOwner. Hidden or removed pages stop their
 * StateFlow collectors even though the hosting Activity stays alive.
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

    init {
        activity.lifecycle.addObserver(this)
        activity.onBackPressedDispatcher.addCallback(
            activity,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
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

    fun push(entry: Entry) {
        entries.lastOrNull()?.hide()
        attach(entry)
        entries.addLast(entry)
        entry.show(hostStarted)
    }

    fun pop(): Boolean {
        if (entries.size <= 1) return false
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
}
