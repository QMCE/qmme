package rj.qmme.ui

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.google.android.material.motion.MotionUtils

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
            spring = SpringForce(to.toFloat()).apply {
                stiffness = force.stiffness
                dampingRatio = force.dampingRatio
            }
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
