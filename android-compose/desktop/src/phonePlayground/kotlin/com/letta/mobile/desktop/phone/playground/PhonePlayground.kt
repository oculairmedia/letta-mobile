package com.letta.mobile.desktop.phone.playground

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.letta.mobile.desktop.DesktopMaterialTheme
import com.letta.mobile.desktop.DesktopThemeOverride
import com.letta.mobile.desktop.LocalDesktopThemeOverride
import com.letta.mobile.desktop.avatar.rive.DesktopMascotHost
import com.letta.mobile.desktop.avatar.rive.RiveBridgeNative
import com.letta.mobile.desktop.initializeDesktopLifecycleMainThread
import com.letta.mobile.desktop.phone.DesktopPhoneScreen
import com.letta.mobile.desktop.phone.DesktopPhoneState
import com.letta.mobile.desktop.phone.DesktopPhoneTouchPointer
import com.letta.mobile.desktop.phone.PhoneDevice
import com.letta.mobile.desktop.phone.PhoneShortcut
import com.letta.mobile.desktop.phone.apply
import com.letta.mobile.desktop.phone.reportRiveBridge
import com.letta.mobile.desktop.phone.PhoneScreenshots
import com.letta.mobile.ui.chat.session.ChatSurfaceModeReducer
import com.letta.mobile.ui.devfixtures.FixtureChatSessionPort
import com.letta.mobile.ui.devfixtures.FixtureMascotShell
import com.letta.mobile.ui.devfixtures.PhoneScene
import com.letta.mobile.ui.devfixtures.PhoneSceneSurface
import com.letta.mobile.ui.devfixtures.PhoneScenes
import com.letta.mobile.ui.devfixtures.StandInMascotHost
import com.letta.mobile.ui.theme.LettaDimens

/*
 * `:desktop:runPhonePlayground` (docs/development/phone-preview.md): the shared chat page's phone
 * screens - the same PhoneScenes sharedUI's phone snapshot tests render - live and interactive in
 * phone-sized panes, with no server. Click a screen to show it; Ctrl+click (or Shift+click) adds it
 * beside the others, up to [MAX_PANES]. The phone shortcuts (F1) apply to every pane.
 *
 * Environment, for scripted runs: LETTA_PLAYGROUND_SCENES=<id,id,...> picks the first panes;
 * LETTA_PHONE_AUTOSHOT=<seconds> saves a screenshot that long after opening, and
 * LETTA_PHONE_EXIT_AFTER_SHOT=1 then quits.
 */

fun main() {
    initializeDesktopLifecycleMainThread()
    println("PHONE: phone playground - ${PhoneScenes.all.size} screens from sharedUI-devfixtures. Press F1 for the shortcuts.")
    reportRiveBridge()
    application {
        val initial = remember { initialScenes() }
        val shown = remember { mutableStateListOf<PhoneScene>().apply { addAll(initial) } }
        val windowState = rememberWindowState(position = WindowPosition(Alignment.Center), size = playgroundWindowSize(shown.size))
        val presets = remember { DesktopPhoneState(initialDark = true) }
        // One phone state per shown screen, created from the shared presets when the screen appears.
        val panes = remember { mutableMapOf<String, DesktopPhoneState>() }
        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Letta phone playground - ${shown.joinToString { it.label }} - F1 shortcuts",
            onPreviewKeyEvent = { event -> onPlaygroundKey(event, presets, panes.values) },
        ) {
            LaunchedEffect(window) { DesktopPhoneTouchPointer.install(window) { presets.touchPointer } }
            PhoneScreenshots(window, presets, "phone-playground", ::exitApplication)
            CompositionLocalProvider(LocalDesktopThemeOverride provides DesktopThemeOverride(dark = presets.dark, phoneMetrics = true)) {
                DesktopMaterialTheme {
                    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest)) {
                        SceneList(
                            shown = shown,
                            onPick = { scene, add -> pick(shown, scene, add) },
                            modifier = Modifier.width(LIST_WIDTH).fillMaxHeight(),
                        )
                        PaneRow(shown, presets, panes, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

private fun initialScenes(): List<PhoneScene> {
    val requested = System.getenv("LETTA_PLAYGROUND_SCENES")?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
    val picked = requested.mapNotNull { id -> PhoneScenes.all.firstOrNull { it.id == id } }.take(MAX_PANES)
    return picked.ifEmpty { listOf(PhoneScenes.fullScreenDark, PhoneScenes.canvasReplyPopup) }
}

private fun pick(shown: MutableList<PhoneScene>, scene: PhoneScene, add: Boolean) {
    if (!add) {
        shown.clear()
        shown += scene
        return
    }
    if (scene in shown) {
        if (shown.size > 1) shown -= scene
        return
    }
    if (shown.size >= MAX_PANES) shown.removeAt(0)
    shown += scene
}

private fun onPlaygroundKey(event: KeyEvent, presets: DesktopPhoneState, panes: Collection<DesktopPhoneState>): Boolean {
    val shortcut = PhoneShortcut.of(event) ?: return false
    presets.apply(shortcut)
    // Every pane follows the presets; the keyboard and the theme flip on each, from its own state.
    if (shortcut != PhoneShortcut.Screenshot) panes.forEach { pane -> pane.apply(shortcut) }
    return true
}

@Composable
private fun SceneList(
    shown: List<PhoneScene>,
    onPick: (PhoneScene, add: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column {
            Text(
                "Phone screens",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(LettaDimens.Space.lg),
            )
            Text(
                "Click to show. Ctrl+click to add beside (up to $MAX_PANES). F1 for the phone shortcuts.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = LettaDimens.Space.lg),
            )
            HorizontalDivider(Modifier.padding(top = LettaDimens.Space.md))
            LazyColumn(Modifier.fillMaxSize()) {
                items(PhoneScenes.all, key = { it.id }) { scene ->
                    SceneRow(scene, selected = scene in shown, onPick = onPick)
                }
            }
        }
    }
}

@Composable
private fun SceneRow(scene: PhoneScene, selected: Boolean, onPick: (PhoneScene, Boolean) -> Unit) {
    var modifiers by remember { mutableStateOf(PointerKeyboardModifiers()) }
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) colors.secondaryContainer else colors.surfaceContainerLow)
            .trackKeyboardModifiers { modifiers = it }
            .clickable { onPick(scene, modifiers.isCtrlPressed || modifiers.isShiftPressed) }
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(scene.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                scene.id,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Remembers the keyboard modifiers of the last press on this element (a click carries none of its own). */
private fun Modifier.trackKeyboardModifiers(onChange: (PointerKeyboardModifiers) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type == PointerEventType.Press) onChange(event.keyboardModifiers)
            }
        }
    }

@Composable
private fun PaneRow(
    shown: List<PhoneScene>,
    presets: DesktopPhoneState,
    panes: MutableMap<String, DesktopPhoneState>,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.padding(LettaDimens.Space.lg)) {
        val device = PhoneDevice.Pixel9Pro
        val labelHeight = LABEL_HEIGHT
        val gap = LettaDimens.Space.lg
        // The tallest phone that fits the height, and the width that leaves for each.
        val byHeight = ((maxHeight - labelHeight - FRAME * 2) / device.heightDp).value
        val byWidth = (((maxWidth - gap * (shown.size - 1)) / shown.size.coerceAtLeast(1) - FRAME * 2) / device.widthDp).value
        val zoom = minOf(byHeight, byWidth).coerceAtLeast(MIN_PANE_ZOOM)
        val paneSize = DpSize((device.widthDp * zoom).dp + FRAME * 2, (device.heightDp * zoom).dp + FRAME * 2)
        val shownIds = shown.map { it.id }
        LaunchedEffect(shownIds, zoom) { println("PHONE: playground showing $shownIds at zoom ${"%.2f".format(zoom)}") }
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            shown.forEach { scene ->
                val pane = panes.getOrPut(scene.id) { paneStateFor(scene, presets) }
                if (pane.zoom != zoom) pane.zoom = zoom
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        scene.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = LettaDimens.Space.sm),
                    )
                    DesktopPhoneScreen(pane, Modifier.size(paneSize)) { ScenePane(scene, pane) }
                }
            }
        }
    }
}

/** A pane's phone: the shared presets, the screen's own theme, and its keyboard if the screen has it up. */
private fun paneStateFor(scene: PhoneScene, presets: DesktopPhoneState) = DesktopPhoneState(initialDark = scene.dark).apply {
    landscape = presets.landscape
    fontScale = presets.fontScale
    displaySize = presets.displaySize
    reducedMotion = presets.reducedMotion
    deviceFrame = presets.deviceFrame
    keyboardVisible = scene.keyboardUp
    // A fixture screen keeps its keyboard as the screen has it; Ctrl+K moves it.
    autoKeyboard = !scene.keyboardUp
    showShortcuts = false
}

@Composable
private fun ScenePane(scene: PhoneScene, pane: DesktopPhoneState) {
    // The native mascot draws one surface per agent, so it only takes a single pane.
    val rive = RiveBridgeNative.AVAILABLE && System.getenv("LETTA_PLAYGROUND_RIVE") == "1"
    FixtureMascotShell(host = if (rive) DesktopMascotHost else StandInMascotHost) {
        CompositionLocalProvider(LocalDesktopThemeOverride provides DesktopThemeOverride(dark = pane.dark, phoneMetrics = true)) {
            DesktopMaterialTheme {
                val port = remember(scene) { FixtureChatSessionPort(scene.state, scene.composer) }
                var presentation by remember(scene) { mutableStateOf(scene.presentation) }
                var dock by remember(scene) { mutableStateOf(scene.dock) }
                Box(Modifier.fillMaxSize()) {
                    PhoneSceneSurface(
                        scene = scene,
                        port = port,
                        presentation = presentation,
                        onIntent = { intent -> presentation = ChatSurfaceModeReducer.reduce(presentation, intent) },
                        onDockGeometryChange = { dock = it },
                        dock = dock,
                    )
                }
            }
        }
    }
}

private fun playgroundWindowSize(panes: Int): DpSize {
    val zoom = com.letta.mobile.desktop.phone.phoneZoomFor(PhoneDevice.Pixel9Pro)
    val device = PhoneDevice.Pixel9Pro
    val paneWidth = device.widthDp * zoom + FRAME.value * 2
    val width = LIST_WIDTH.value + panes * (paneWidth + GAP_DP) + GAP_DP
    val height = device.heightDp * zoom + FRAME.value * 2 + LABEL_HEIGHT.value + GAP_DP * 2 + TITLE_BAR_DP
    // Never larger than the usable screen; the panes shrink to fit whatever the window is.
    val usable = runCatching { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds }.getOrNull()
    val maxWidth = usable?.width?.toFloat() ?: width
    val maxHeight = usable?.height?.toFloat() ?: height
    return DpSize(minOf(width, maxWidth).dp, minOf(height, maxHeight).dp)
}

private const val MAX_PANES = 3
private val LIST_WIDTH: Dp = 260.dp
private val LABEL_HEIGHT: Dp = 28.dp
private val FRAME: Dp = 10.dp
private const val GAP_DP = 16f
private const val TITLE_BAR_DP = 40f
private const val MIN_PANE_ZOOM = 0.3f
