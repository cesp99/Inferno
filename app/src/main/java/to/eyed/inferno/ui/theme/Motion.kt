package to.eyed.inferno.ui.theme

import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch

// One motion system. Every finite spec is derived from the theme's MotionScheme so M3 sheets,
// menus and the app's own controls share one character. This file is the only place in ui/
// allowed to mention spring()/tween(): the infinite loops below cannot come from a MotionScheme.

/** Default press-scale for custom pills; ghost buttons use 0.92, primary 0.97, send 0.94. */
const val PressScale = 0.96f

/** Press-scale, pill slides, knob travel. */
@Composable
fun pressSpec(): FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.fastSpatialSpec()

/** Size changes (animateContentSize, expanding panels). */
@Composable
fun layoutSpec(): FiniteAnimationSpec<IntSize> = MaterialTheme.motionScheme.defaultSpatialSpec()

@Composable
fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.fastSpatialSpec()

@Composable
fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.defaultSpatialSpec()

/** Colour and alpha, short. */
@Composable
fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.fastEffectsSpec()

/** Colour and alpha, screen-level. */
@Composable
fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = MaterialTheme.motionScheme.defaultEffectsSpec()

// ---- Infinite loops (the only tween() calls in ui/) --------------------------------------------

/** Full breathing cycle of the brand mark: 7 s out and back. */
const val MorphCycleMs = 7_000

private val EaseInOut = CubicBezierEasing(0.45f, 0f, 0.55f, 1f)

/** Half a morph cycle (circle -> cookie); played twice for the FirstRun single cycle. */
fun morphHalfCycleSpec(): TweenSpec<Float> = tween(MorphCycleMs / 2, easing = EaseInOut)

/** Endless breathing while the engine is loading or a turn is busy. */
fun morphLoopSpec(): InfiniteRepeatableSpec<Float> =
    infiniteRepeatable(morphHalfCycleSpec(), RepeatMode.Reverse)

/** Streaming caret: 450 ms on/off. */
fun caretBlinkSpec(): InfiniteRepeatableSpec<Float> =
    infiniteRepeatable(tween(450, easing = LinearEasing), RepeatMode.Reverse)

/** Pulsing dot; stagger sibling dots with [delayMs] (200 ms apart). */
fun pulseSpec(delayMs: Int = 0): InfiniteRepeatableSpec<Float> =
    infiniteRepeatable(tween(600, easing = EaseInOut), RepeatMode.Reverse, StartOffset(delayMs))

// ---- Reduced motion / haptics ------------------------------------------------------------------

/** The user's "Streaming animations" setting; Root provides it from SettingsState. */
val LocalAnimations = compositionLocalOf { true }

/** The user's "Haptics" setting; Root provides it from SettingsState. */
val LocalHaptics = compositionLocalOf { true }

/**
 * True when infinite/decorative animations may run: the setting is on AND the system animator
 * scale is not zero. Springs still run when this is false (they are short).
 */
@Composable
fun animationsEnabled(): Boolean {
    val setting = LocalAnimations.current
    if (LocalInspectionMode.current) return setting
    val context = LocalContext.current
    // Read once per composition owner: the system value only changes in developer options.
    val systemScale = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        }.getOrDefault(1f)
    }
    return setting && systemScale != 0f
}

private fun HapticFeedback.perform(enabled: Boolean, type: HapticFeedbackType) {
    if (enabled) performHapticFeedback(type)
}

/** Light tick for taps that need acknowledgement (menu items, chips). */
fun HapticFeedback.tap(enabled: Boolean = true) = perform(enabled, HapticFeedbackType.ContextClick)

/** Generation done, model ready, download complete. */
fun HapticFeedback.confirm(enabled: Boolean = true) = perform(enabled, HapticFeedbackType.Confirm)

/** Errors. */
fun HapticFeedback.reject(enabled: Boolean = true) = perform(enabled, HapticFeedbackType.Reject)

/** Long-press menus. */
fun HapticFeedback.longPress(enabled: Boolean = true) = perform(enabled, HapticFeedbackType.LongPress)

/** Send: GestureEnd on API 34+, else Confirm. */
fun HapticFeedback.gestureEnd(enabled: Boolean = true) = perform(
    enabled,
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) HapticFeedbackType.GestureEnd
    else HapticFeedbackType.Confirm,
)

/** Toggles: ToggleOn/ToggleOff are API 34+ constants; older devices get the plain confirm tick. */
fun HapticFeedback.toggle(on: Boolean, enabled: Boolean = true) = perform(
    enabled,
    when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> HapticFeedbackType.Confirm
        on -> HapticFeedbackType.ToggleOn
        else -> HapticFeedbackType.ToggleOff
    },
)

/** Haptics bound to [LocalHaptics]; every call is a no-op when the setting is off. */
@Stable
class Haptics(private val feedback: HapticFeedback, val enabled: Boolean) {
    fun tap() = feedback.tap(enabled)
    fun confirm() = feedback.confirm(enabled)
    fun reject() = feedback.reject(enabled)
    fun longPress() = feedback.longPress(enabled)
    fun gestureEnd() = feedback.gestureEnd(enabled)
    fun toggle(on: Boolean) = feedback.toggle(on, enabled)
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    val enabled = LocalHaptics.current
    return remember(feedback, enabled) { Haptics(feedback, enabled) }
}

// ---- Press feedback ----------------------------------------------------------------------------

/**
 * Scales the element to [scale] while [interactionSource] is pressed. The animated value is read
 * inside `graphicsLayer`, so press feedback never recomposes — important while text streams.
 */
@Composable
fun Modifier.pressScale(interactionSource: InteractionSource, scale: Float = PressScale): Modifier {
    val spec = pressSpec()
    val animated = remember { Animatable(1f) }
    LaunchedEffect(interactionSource, scale, spec) {
        interactionSource.interactions.collect { interaction ->
            val target = when (interaction) {
                is PressInteraction.Press -> scale
                is PressInteraction.Release, is PressInteraction.Cancel -> 1f
                else -> return@collect
            }
            launch { animated.animateTo(target, spec) }
        }
    }
    return graphicsLayer {
        scaleX = animated.value
        scaleY = animated.value
    }
}
