@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package com.letta.mobile.desktop.phone

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Test tags for the phone screen's simulated system chrome. */
internal object PhoneScreenTags {
    const val SCREEN = "phone-screen"
    const val STATUS_BAR = "phone-status-bar"
    const val GESTURE_BAR = "phone-gesture-bar"
    const val KEYBOARD = "phone-keyboard"
    const val SHORTCUTS = "phone-shortcuts"
}

/**
 * The phone's screen inside a desktop window: [content] laid out at phone density and size, under a
 * simulated status bar, gesture bar and on-screen keyboard, with the window insets a phone reports
 * (see [PhoneWindowInsets]). The screen fills the window (inside an optional thin device frame), so
 * resizing the window resizes the phone in dp.
 */
@Composable
internal fun DesktopPhoneScreen(
    state: DesktopPhoneState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val windowDensity = LocalDensity.current
    val frame = phoneFrameStyle(state)
    Box(modifier.fillMaxSize().background(frame.backdrop)) {
        BoxWithConstraints(Modifier.fillMaxSize().then(frame.screen).testTag(PhoneScreenTags.SCREEN)) {
            PhoneScreenContent(state, windowDensity, constraints, content)
        }
    }
}

/** What surrounds the screen: the bezel and the screen's rounded, rimmed glass, or nothing. */
private class PhoneFrameStyle(val backdrop: Color, val screen: Modifier)

private fun phoneFrameStyle(state: DesktopPhoneState): PhoneFrameStyle {
    if (!state.deviceFrame) return PhoneFrameStyle(Color.Black, Modifier)
    val glass = RoundedCornerShape((state.device.cornerRadiusDp * state.zoom).dp)
    val bezel = if (state.dark) BEZEL_DARK else BEZEL_LIGHT
    return PhoneFrameStyle(bezel, Modifier.padding(FRAME_WIDTH).border(FRAME_RIM, FRAME_RIM_COLOR, glass).clip(glass))
}

/** The screen at phone density: the app, its insets, the keyboard's slide and the system chrome. */
@Composable
private fun PhoneScreenContent(
    state: DesktopPhoneState,
    windowDensity: Density,
    constraints: Constraints,
    content: @Composable () -> Unit,
) {
    val phoneDensity = remember(windowDensity, state.zoom, state.displaySize, state.fontScale) {
        Density(windowDensity.density * state.zoom * state.displaySize, state.fontScale)
    }
    SideEffect { state.reportScreenSize(constraints, phoneDensity) }
    val keyboard = rememberKeyboardSlide(state)
    val currentDensity = rememberUpdatedState(phoneDensity)
    val insets = remember(state, keyboard) {
        PhoneWindowInsets(PhoneInsetsState(state, densityOf = { currentDensity.value }, imeFractionOf = { keyboard.value }))
    }
    CompositionLocalProvider(
        LocalDensity provides phoneDensity,
        LocalReducedMotion provides state.reducedMotion,
    ) {
        PhoneTextInputKeyboard(state) {
            ProvidePhoneWindowInsets(insets, Modifier.fillMaxSize()) {
                content()
                PhoneSystemChrome(state, keyboard.value)
            }
        }
    }
}

private fun DesktopPhoneState.reportScreenSize(constraints: Constraints, density: Density) {
    screenWidthDp = (constraints.maxWidth / density.density).toInt()
    screenHeightDp = (constraints.maxHeight / density.density).toInt()
}

/** The keyboard's slide, 0 (down) to 1 (up); the insets read it on every frame of the slide. */
@Composable
private fun rememberKeyboardSlide(state: DesktopPhoneState): Animatable<Float, AnimationVector1D> {
    val keyboard = remember { Animatable(state.keyboardTarget()) }
    LaunchedEffect(state.keyboardVisible, state.reducedMotion) { keyboard.slideTo(state) }
    return keyboard
}

private fun DesktopPhoneState.keyboardTarget(): Float = if (keyboardVisible) 1f else 0f

/** With reduced motion the keyboard snaps, as Android skips the IME animation. */
private suspend fun Animatable<Float, AnimationVector1D>.slideTo(state: DesktopPhoneState) {
    val target = state.keyboardTarget()
    if (state.reducedMotion) snapTo(target) else animateTo(target, KEYBOARD_SLIDE)
}

/** [PhoneInsetsSource] over the phone state, the screen's density and the keyboard's slide (0 to 1). */
@Stable
internal class PhoneInsetsState(
    private val state: DesktopPhoneState,
    private val densityOf: () -> Density,
    private val imeFractionOf: () -> Float,
) : PhoneInsetsSource {
    override val density: Density get() = densityOf()

    override val statusBarDp: Float get() = state.device.statusBarDp.toFloat()
    override val navigationBarDp: Float get() = state.device.navigationBarDp.toFloat()

    /** As Android reports it: the keyboard plus the gesture bar under it, scaled while it slides. */
    override val imeDp: Float
        get() = (state.keyboardDp + state.device.navigationBarDp) * imeFractionOf().coerceAtLeast(0f)
}

/**
 * Raises the simulated keyboard whenever a text field starts an input session and lowers it a beat
 * after the last one ends, as Android does. A field that moves (the composer, between the canvas bar
 * and the chat page) restarts its session, and the grace keeps the keyboard from blinking.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PhoneTextInputKeyboard(state: DesktopPhoneState, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val interceptor = remember(state, scope) {
        val sessions = KeyboardSessions(state, scope)
        PlatformTextInputInterceptor { request, nextHandler ->
            sessions.begin()
            try {
                nextHandler.startInputMethod(request)
            } finally {
                sessions.end()
            }
        }
    }
    InterceptPlatformTextInput(interceptor = interceptor, content = content)
}

/** Counts the text input sessions open on the screen and drives the keyboard from them. */
private class KeyboardSessions(private val state: DesktopPhoneState, private val scope: CoroutineScope) {
    private var open = 0

    fun begin() {
        open += 1
        if (state.autoKeyboard) state.keyboardVisible = true
    }

    fun end() {
        open -= 1
        scope.launch {
            delay(KEYBOARD_HIDE_GRACE_MILLIS)
            hideIfIdle()
        }
    }

    private fun hideIfIdle() {
        if (open == 0 && state.autoKeyboard) state.keyboardVisible = false
    }
}

private val FRAME_WIDTH: Dp = 10.dp
private val FRAME_RIM: Dp = 1.dp
private val FRAME_RIM_COLOR = Color(0xFF3A3D42)
private val BEZEL_DARK = Color(0xFF0B0C0E)
private val BEZEL_LIGHT = Color(0xFF1B1D21)

/** Close to Android's IME inset animation: ~285 ms on its fast-out-slow-in curve. */
private val KEYBOARD_SLIDE = tween<Float>(285, easing = FastOutSlowInEasing)
private const val KEYBOARD_HIDE_GRACE_MILLIS = 150L
