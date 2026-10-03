package com.letta.mobile.desktop.phone

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * The phone's system chrome over the app: the status bar, the gesture bar, the keyboard (slid in by
 * [imeFraction]), and the preview's own toast and shortcut list. It is the OS's chrome, not the
 * app's, so it follows the phone's light or dark theme rather than the app's tokens.
 */
@Composable
internal fun PhoneSystemChrome(state: DesktopPhoneState, imeFraction: Float) {
    MaterialTheme(colorScheme = systemColors(state.dark)) {
        Box(Modifier.fillMaxSize()) {
            PhoneStatusBar(state, Modifier.align(Alignment.TopCenter))
            PhoneKeyboard(state, imeFraction, Modifier.align(Alignment.BottomCenter))
            PhoneGestureBar(state, Modifier.align(Alignment.BottomCenter))
            PhoneToast(state, Modifier.align(Alignment.TopCenter).padding(top = state.device.statusBarDp.dp + TOAST_GAP))
            PhoneShortcutList(state, Modifier.align(Alignment.Center))
        }
    }
}

private fun systemColors(dark: Boolean): ColorScheme = if (dark) darkColorScheme() else lightColorScheme()

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
            Battery(ink)
        }
    }
}

@Composable
private fun SignalBars(ink: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(SIGNAL_GAP), verticalAlignment = Alignment.Bottom) {
        SIGNAL_BAR_HEIGHTS.forEach { height -> Box(Modifier.size(SIGNAL_BAR_WIDTH, height).background(ink)) }
    }
}

/** A body with a nub. */
@Composable
private fun Battery(ink: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(BATTERY_WIDTH, BATTERY_HEIGHT).background(ink, RoundedCornerShape(BATTERY_CORNER)))
        Box(Modifier.size(BATTERY_NUB_WIDTH, BATTERY_NUB_HEIGHT).background(ink))
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
 * Nothing is drawn while it is fully down.
 */
@Composable
private fun PhoneKeyboard(state: DesktopPhoneState, fraction: Float, modifier: Modifier) {
    if (fraction <= 0f) return
    val navigation = state.device.navigationBarDp.dp
    val height = state.keyboardDp.dp + navigation
    val offset = with(LocalDensity.current) { height.toPx() } * (1f - fraction)
    Column(
        modifier
            .fillMaxWidth()
            .height(height)
            .graphicsLayer { translationY = offset }
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .swallowPointerInput()
            .testTag(PhoneScreenTags.KEYBOARD),
    ) {
        SuggestionStrip(onHide = { state.keyboardVisible = false })
        Column(Modifier.fillMaxWidth().weight(1f).padding(KEY_GAP)) {
            KEY_ROWS.forEach { row -> KeyRow(row) }
        }
        Spacer(Modifier.height(navigation))
    }
}

/** Takes every touch on the keyboard, as the IME window would. */
private fun Modifier.swallowPointerInput(): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) awaitPointerEvent().changes.forEach { it.consume() }
    }
}

@Composable
private fun SuggestionStrip(onHide: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(SUGGESTION_STRIP_HEIGHT).padding(horizontal = KEY_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "⌄",
            color = colors.onSurfaceVariant,
            fontSize = SUGGESTION_TEXT_SIZE,
            modifier = Modifier.clickable(onClick = onHide).padding(horizontal = KEY_GAP * 2),
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
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.KeyRow(keys: List<PhoneKey>) {
    Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(KEY_GAP)) {
        keys.forEach { key -> KeyCap(key) }
    }
    Spacer(Modifier.height(KEY_GAP))
}

/** A key cap: its label, how wide it is, and whether it is a function key (tinted). */
private class PhoneKey(val label: String, val weight: Float = 1f, val function: Boolean = false)

@Composable
private fun RowScope.KeyCap(key: PhoneKey) {
    val colors = MaterialTheme.colorScheme
    val fill = if (key.function) colors.secondaryContainer else colors.surfaceContainerHighest
    Box(
        Modifier
            .weight(key.weight)
            .fillMaxHeight()
            .background(fill, RoundedCornerShape(KEY_CORNER)),
        contentAlignment = Alignment.Center,
    ) {
        Text(key.label, color = colors.onSurface, fontSize = KEY_TEXT_SIZE)
    }
}

@Composable
private fun PhoneToast(state: DesktopPhoneState, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    if (state.toast != null) {
        LaunchedEffect(state.toastSerial) {
            visible = true
            delay(TOAST_MILLIS)
            visible = false
        }
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
                PhoneShortcut.entries.forEach { shortcut -> ShortcutRow(shortcut) }
                Text(
                    "Click to dismiss",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ShortcutRow(shortcut: PhoneShortcut) {
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
private const val SPACE_WEIGHT = 4f
private const val WIDE_KEY_WEIGHT = 1.5f
private val KEY_ROWS: List<List<PhoneKey>> = listOf(
    "qwertyuiop".map { PhoneKey(it.toString()) },
    "asdfghjkl".map { PhoneKey(it.toString()) },
    listOf(PhoneKey("⇧")) + "zxcvbnm".map { PhoneKey(it.toString()) } + PhoneKey("⌫"),
    listOf(
        PhoneKey("?123", WIDE_KEY_WEIGHT, function = true),
        PhoneKey(","),
        PhoneKey("", SPACE_WEIGHT),
        PhoneKey("."),
        PhoneKey("⏎"),
    ),
)
private const val OVERLAY_ALPHA = 0.96f
private val OVERLAY_ELEVATION: Dp = 6.dp
private val OVERLAY_MARGIN: Dp = 16.dp
private val OVERLAY_PADDING: Dp = 16.dp
private val OVERLAY_ROW_GAP: Dp = 4.dp
private val OVERLAY_KEY_COLUMN: Dp = 104.dp
