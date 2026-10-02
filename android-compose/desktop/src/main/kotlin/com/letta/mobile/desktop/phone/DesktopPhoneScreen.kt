@file:OptIn(androidx.compose.ui.InternalComposeUiApi::class)

package com.letta.mobile.desktop.phone

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.letta.mobile.ui.theme.LocalReducedMotion
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
    val frame = state.deviceFrame
    val bezel = if (state.dark) BEZEL_DARK else BEZEL_LIGHT
    Box(modifier.fillMaxSize().background(if (frame) bezel else Color.Black)) {
        val screenShape = if (frame) RoundedCornerShape((state.device.cornerRadiusDp * state.zoom).dp) else RoundedCornerShape(0.dp)
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(if (frame) FRAME_WIDTH else 0.dp)
                .then(if (frame) Modifier.border(FRAME_RIM, FRAME_RIM_COLOR, screenShape) else Modifier)
                .clip(screenShape)
                .testTag(PhoneScreenTags.SCREEN),
        ) {
            val phoneDensity = remember(windowDensity, state.zoom, state.displaySize, state.fontScale) {
                Density(windowDensity.density * state.zoom * state.displaySize, state.fontScale)
            }
            val widthDp = (constraints.maxWidth / phoneDensity.density).toInt()
            val heightDp = (constraints.maxHeight / phoneDensity.density).toInt()
            SideEffect {
                state.screenWidthDp = widthDp
                state.screenHeightDp = heightDp
            }
            // The keyboard's slide, 0 (down) to 1 (up); the insets read it on every frame of the slide.
            val keyboard = remember { Animatable(if (state.keyboardVisible) 1f else 0f) }
            LaunchedEffect(state.keyboardVisible, state.reducedMotion) {
                val target = if (state.keyboardVisible) 1f else 0f
                if (state.reducedMotion) keyboard.snapTo(target) else keyboard.animateTo(target, KEYBOARD_SLIDE)
            }
            val currentDensity = rememberUpdatedState(phoneDensity)
            val insets = remember(state, keyboard) {
                PhoneWindowInsets(PhoneInsetsState(state, densityOf = { currentDensity.value }, imeFractionOf = { keyboard.value }))
            }
            val imeFraction = keyboard.value
            CompositionLocalProvider(
                LocalDensity provides phoneDensity,
                LocalReducedMotion provides state.reducedMotion,
            ) {
                PhoneTextInputKeyboard(state) {
                    ProvidePhoneWindowInsets(insets, Modifier.fillMaxSize()) {
                        content()
                        PhoneSystemChrome(state, imeFraction)
                    }
                }
            }
        }
    }
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
        get() {
            val fraction = imeFractionOf()
            return if (fraction <= 0f) 0f else (state.keyboardDp + state.device.navigationBarDp) * fraction
        }
}

/**
 * Raises the simulated keyboard whenever a text field starts an input session and lowers it a beat
 * after the last one ends (a field that moves - the composer between the canvas bar and the chat
 * page - restarts its session, and the keyboard should not blink), as Android does.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PhoneTextInputKeyboard(state: DesktopPhoneState, content: @Composable () -> Unit) {
    val scope = rememberCoroutineScope()
    val sessions = remember { intArrayOf(0) }
    val interceptor = remember(state, scope) {
        PlatformTextInputInterceptor { request, nextHandler ->
            sessions[0] += 1
            if (state.autoKeyboard) state.keyboardVisible = true
            try {
                nextHandler.startInputMethod(request)
            } finally {
                sessions[0] -= 1
                scope.launch {
                    delay(KEYBOARD_HIDE_GRACE_MILLIS)
                    if (sessions[0] == 0 && state.autoKeyboard) state.keyboardVisible = false
                }
            }
        }
    }
    InterceptPlatformTextInput(interceptor = interceptor, content = content)
}

/** The status bar, the gesture bar, the keyboard, and the preview's own toast and shortcut list. */
@Composable
private fun PhoneSystemChrome(state: DesktopPhoneState, imeFraction: Float) {
    // The OS's chrome, not the app's: it follows the phone's theme, not the app's tokens.
    MaterialTheme(colorScheme = if (state.dark) darkColorScheme() else lightColorScheme()) {
        Box(Modifier.fillMaxSize()) {
            PhoneStatusBar(state, Modifier.align(Alignment.TopCenter))
            if (imeFraction > 0f) PhoneKeyboard(state, imeFraction, Modifier.align(Alignment.BottomCenter))
            PhoneGestureBar(state, Modifier.align(Alignment.BottomCenter))
            PhoneToast(state, Modifier.align(Alignment.TopCenter).padding(top = state.device.statusBarDp.dp + TOAST_GAP))
            PhoneShortcutList(state, Modifier.align(Alignment.Center))
        }
    }
}

@Composable
private fun PhoneStatusBar(state: DesktopPhoneState, modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.onBackground
    Box(modifier.fillMaxWidth().height(state.device.statusBarDp.dp).testTag(PhoneScreenTags.STATUS_BAR)) {
        Text(
            "10:18",
            color = ink,
            fontSize = STATUS_TEXT_SIZE,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = STATUS_SIDE_PADDING),
        )
        // The front camera's punch hole.
        Box(Modifier.align(Alignment.Center).size(CAMERA_SIZE).background(Color.Black, CircleShape))
        Row(
            Modifier.align(Alignment.CenterEnd).padding(end = STATUS_SIDE_PADDING),
            horizontalArrangement = Arrangement.spacedBy(STATUS_ICON_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SignalBars(ink)
            // The battery: a body with a nub.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(BATTERY_WIDTH, BATTERY_HEIGHT).background(ink, RoundedCornerShape(BATTERY_CORNER)))
                Box(Modifier.size(BATTERY_NUB_WIDTH, BATTERY_NUB_HEIGHT).background(ink))
            }
        }
    }
}

@Composable
private fun SignalBars(ink: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(SIGNAL_GAP), verticalAlignment = Alignment.Bottom) {
        SIGNAL_BAR_HEIGHTS.forEach { height -> Box(Modifier.size(SIGNAL_BAR_WIDTH, height).background(ink)) }
    }
}

@Composable
private fun PhoneGestureBar(state: DesktopPhoneState, modifier: Modifier) {
    Box(
        modifier.fillMaxWidth().height(state.device.navigationBarDp.dp).testTag(PhoneScreenTags.GESTURE_BAR),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(GESTURE_HANDLE_WIDTH, GESTURE_HANDLE_HEIGHT)
                .background(MaterialTheme.colorScheme.onBackground.copy(alpha = GESTURE_HANDLE_ALPHA), CircleShape),
        )
    }
}

/**
 * A stand-in for Gboard: its height and its slide are what the app reacts to, so it only has to look
 * the part. Its keys do not type (the desktop keyboard does); the chevron hides it, as Back does.
 */
@Composable
private fun PhoneKeyboard(state: DesktopPhoneState, fraction: Float, modifier: Modifier) {
    val navigation = state.device.navigationBarDp.dp
    val height = state.keyboardDp.dp + navigation
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    Column(
        modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { translationY = with(density) { height.toPx() } * (1f - fraction) }
            .background(colors.surfaceContainerHigh)
            // Swallow every touch on the keyboard, as the IME window would.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
            .testTag(PhoneScreenTags.KEYBOARD),
    ) {
        Row(
            Modifier.fillMaxWidth().height(SUGGESTION_STRIP_HEIGHT).padding(horizontal = KEY_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "⌄",
                color = colors.onSurfaceVariant,
                fontSize = SUGGESTION_TEXT_SIZE,
                modifier = Modifier.clickable { state.keyboardVisible = false }.padding(horizontal = KEY_GAP * 2),
            )
            SUGGESTIONS.forEach { word ->
                Text(
                    word,
                    color = colors.onSurface,
                    fontSize = SUGGESTION_TEXT_SIZE,
                    modifier = Modifier.weight(1f).padding(horizontal = KEY_GAP),
                    maxLines = 1,
                )
            }
        }
        Column(Modifier.fillMaxWidth().weight(1f).padding(KEY_GAP)) {
            KEY_ROWS.forEach { row ->
                Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
                    row.forEach { key -> KeyCap(key) }
                }
                Spacer(Modifier.height(KEY_GAP))
            }
        }
        Spacer(Modifier.height(navigation))
    }
}

@Composable
private fun RowScope.KeyCap(label: String) {
    val colors = MaterialTheme.colorScheme
    val wide = label.length > 1
    Box(
        Modifier
            .weight(if (label == SPACE) SPACE_WEIGHT else if (wide) WIDE_KEY_WEIGHT else 1f)
            .fillMaxHeight()
            .background(if (wide) colors.secondaryContainer else colors.surfaceContainerHighest, RoundedCornerShape(KEY_CORNER)),
        contentAlignment = Alignment.Center,
    ) {
        Text(if (label == SPACE) "" else label, color = colors.onSurface, fontSize = KEY_TEXT_SIZE)
    }
}

@Composable
private fun PhoneToast(state: DesktopPhoneState, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(state.toastSerial) {
        if (state.toast == null) return@LaunchedEffect
        visible = true
        delay(TOAST_MILLIS)
        visible = false
    }
    AnimatedVisibility(visible, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Surface(
            shape = RoundedCornerShape(TOAST_CORNER),
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ) {
            Text(state.toast.orEmpty(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(TOAST_PADDING))
        }
    }
}

@Composable
private fun PhoneShortcutList(state: DesktopPhoneState, modifier: Modifier) {
    AnimatedVisibility(state.showShortcuts, modifier = modifier, enter = fadeIn(), exit = fadeOut()) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = OVERLAY_ALPHA),
            tonalElevation = OVERLAY_ELEVATION,
            modifier = Modifier.padding(OVERLAY_MARGIN).clickable { state.showShortcuts = false }.testTag(PhoneScreenTags.SHORTCUTS),
        ) {
            Column(Modifier.padding(OVERLAY_PADDING), verticalArrangement = Arrangement.spacedBy(OVERLAY_ROW_GAP)) {
                Text("Phone preview", style = MaterialTheme.typography.titleSmall)
                PhoneShortcut.entries.forEach { shortcut ->
                    Row {
                        Text(
                            shortcut.keys,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(OVERLAY_KEY_COLUMN),
                        )
                        Text(shortcut.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(
                    "Click to dismiss",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
private val TOAST_GAP: Dp = 12.dp
private const val TOAST_MILLIS = 1600L
private val TOAST_CORNER: Dp = 20.dp
private val TOAST_PADDING: Dp = 12.dp
private val STATUS_TEXT_SIZE = 14.sp
private val STATUS_SIDE_PADDING: Dp = 24.dp
private val STATUS_ICON_GAP: Dp = 6.dp
private val CAMERA_SIZE: Dp = 12.dp
private val BATTERY_WIDTH: Dp = 20.dp
private val BATTERY_HEIGHT: Dp = 10.dp
private val BATTERY_CORNER: Dp = 2.dp
private val BATTERY_NUB_WIDTH: Dp = 2.dp
private val BATTERY_NUB_HEIGHT: Dp = 4.dp
private val SIGNAL_GAP: Dp = 1.5.dp
private val SIGNAL_BAR_WIDTH: Dp = 3.dp
private val SIGNAL_BAR_HEIGHTS = listOf(4.dp, 6.dp, 8.dp, 10.dp)
private val GESTURE_HANDLE_WIDTH: Dp = 108.dp
private val GESTURE_HANDLE_HEIGHT: Dp = 4.dp
private const val GESTURE_HANDLE_ALPHA = 0.7f
private val SUGGESTION_STRIP_HEIGHT: Dp = 44.dp
private val SUGGESTION_TEXT_SIZE = 15.sp
private val SUGGESTIONS = listOf("the", "I", "and")
private val KEY_GAP: Dp = 4.dp
private val KEY_CORNER: Dp = 6.dp
private val KEY_TEXT_SIZE = 18.sp
private const val SPACE = " "
private const val SPACE_WEIGHT = 4f
private const val WIDE_KEY_WEIGHT = 1.5f
private val KEY_ROWS = listOf(
    listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p"),
    listOf("a", "s", "d", "f", "g", "h", "j", "k", "l"),
    listOf("⇧", "z", "x", "c", "v", "b", "n", "m", "⌫"),
    listOf("?123", ",", SPACE, ".", "⏎"),
)
private const val OVERLAY_ALPHA = 0.96f
private val OVERLAY_ELEVATION: Dp = 6.dp
private val OVERLAY_MARGIN: Dp = 16.dp
private val OVERLAY_PADDING: Dp = 16.dp
private val OVERLAY_ROW_GAP: Dp = 4.dp
private val OVERLAY_KEY_COLUMN: Dp = 104.dp
