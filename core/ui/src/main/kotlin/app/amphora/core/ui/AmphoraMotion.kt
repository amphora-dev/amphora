package app.amphora.core.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally

/**
 * Motion vocabulary. Standard emphasized curve, quick exits, one press spring.
 * Apply via NavHost enter/exit/popEnter/popExit and interactive scale effects.
 */
object AmphoraMotion {
    /** Standard emphasized curve (0.4, 0, 0.2, 1). */
    val Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    private const val ENTER_MS = 220
    private const val EXIT_MS = 140

    fun navEnter(): EnterTransition = fadeIn(tween(ENTER_MS, easing = Easing)) +
        slideInHorizontally(tween(ENTER_MS, easing = Easing)) { it / 8 }

    fun navExit(): ExitTransition = fadeOut(tween(EXIT_MS, easing = Easing))

    fun navPopEnter(): EnterTransition = fadeIn(tween(ENTER_MS, easing = Easing)) +
        slideInHorizontally(tween(ENTER_MS, easing = Easing)) { -it / 8 }

    fun navPopExit(): ExitTransition = fadeOut(tween(EXIT_MS, easing = Easing)) +
        slideOutHorizontally(tween(EXIT_MS, easing = Easing)) { it / 8 }

    /** Card press feedback: scale to 0.97 with a snappy spring. */
    fun <T> pressScale() = spring<T>(dampingRatio = 0.7f, stiffness = 400f)
}
