package rj.qmme.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import androidx.transition.TransitionManager
import com.google.android.material.motion.MotionUtils
import com.google.android.material.transition.MaterialFade

/**
 * Material 3 Expressive motion helpers.
 *
 * M3 Expressive replaces "duration + easing curve" with spring physics. The
 * theme already carries the six spring tokens (`motionSpring{Fast,Default,
 * Slow}{Spatial,Effects}`); resolving them keeps QMME's motion identical to
 * every other M3 Expressive surface instead of hand-tuned interpolators.
 *
 * Spatial springs move things (position, size) and are slightly bouncy.
 * Effects springs change non-spatial properties (alpha, color) and are
 * critically damped.
 */
internal object Motion {

    fun fastSpatial(context: Context): SpringForce =
        MotionUtils.resolveThemeSpringForce(
            context,
            com.google.android.material.R.attr.motionSpringFastSpatial,
            com.google.android.material.R.style.Motion_Material3_Spring_Expressive_Fast_Spatial,
        )

    fun defaultSpatial(context: Context): SpringForce =
        MotionUtils.resolveThemeSpringForce(
            context,
            com.google.android.material.R.attr.motionSpringDefaultSpatial,
            com.google.android.material.R.style.Motion_Material3_Spring_Expressive_Default_Spatial,
        )

    fun defaultEffects(context: Context): SpringForce =
        MotionUtils.resolveThemeSpringForce(
            context,
            com.google.android.material.R.attr.motionSpringDefaultEffects,
            com.google.android.material.R.style.Motion_Material3_Spring_Expressive_Default_Effects,
        )

    /**
     * Shows or hides a view with the M3 fade for elements entering and leaving
     * within a container.  The transition is scoped to [view] alone, so its
     * siblings simply re-layout instead of being dragged into the animation.
     *
     * The early return matters as much as the animation: a screen that comes
     * back to STARTED re-subscribes every StateFlow collector and immediately
     * receives the current value again, so without it a plain return from a
     * chat would replay every status/empty-state fade.
     */
    fun fadeVisibility(view: View, visible: Boolean) {
        val target = if (visible) View.VISIBLE else View.GONE
        if (view.visibility == target) return
        val parent = view.parent as? ViewGroup
        if (parent == null || !view.isAttachedToWindow) {
            view.visibility = target
            return
        }
        TransitionManager.beginDelayedTransition(parent, MaterialFade().apply { addTarget(view) })
        view.visibility = target
    }

    /**
     * One-shot reveal for a container that was deliberately drawn transparent
     * because its content was not loaded yet.  Effects springs are critically
     * damped, so alpha lands on 1 without overshooting.
     */
    fun fadeIn(view: View, force: SpringForce) {
        if (view.alpha >= 1f) return
        SpringAnimation(view, DynamicAnimation.ALPHA).apply {
            setSpring(
                SpringForce(1f).apply {
                    stiffness = force.stiffness
                    dampingRatio = force.dampingRatio
                },
            )
            setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_ALPHA)
            start()
        }
    }

    /**
     * Springs a view's layout height, used by the expanding attachment panel.
     * A [FloatValueHolder] drives `layoutParams.height` because height is not
     * a [DynamicAnimation.ViewProperty].
     */
    fun animateHeight(
        view: View,
        from: Int,
        to: Int,
        force: SpringForce,
        onEnd: () -> Unit = {},
    ): SpringAnimation {
        val holder = FloatValueHolder(from.toFloat())
        return SpringAnimation(holder).apply {
            // setSpring is fluent (returns SpringAnimation), so no Kotlin
            // property syntax here.
            setSpring(
                SpringForce(to.toFloat()).apply {
                    stiffness = force.stiffness
                    dampingRatio = force.dampingRatio
                },
            )
            setStartValue(from.toFloat())
            setMinimumVisibleChange(1f)
            addUpdateListener { _, value, _ ->
                val params: ViewGroup.LayoutParams = view.layoutParams ?: return@addUpdateListener
                params.height = value.toInt().coerceAtLeast(0)
                view.layoutParams = params
            }
            addEndListener { _, canceled, _, _ -> if (!canceled) onEnd() }
            start()
        }
    }
}
