package com.letta.mobile.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.lens.WorkPlayLens
import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.ui.icons.LettaIcons
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.KeyEvent
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/** Sidebar toggle test/automation tag, referenced by desktop UI tests. */
internal const val SidebarToggleTestTag = "desktop-sidebar-toggle"

/**
 * Whether the OS has UI animations disabled. Best-effort: AWT's
 * `win.uiEffects.animationsEnabled` desktop property reflects the Windows
 * "Show animations" accessibility setting; other platforms — and any
 * environment where the property is unavailable — fall back to animating
 * normally rather than guessing. `-Dletta.reducedMotion=true` is available
 * as an explicit override for tests/screenshots.
 */
internal fun desktopPrefersReducedMotion(): Boolean {
    if (System.getProperty("letta.reducedMotion") == "true") return true
    return runCatching {
        val toolkit = Toolkit.getDefaultToolkit()
        (toolkit.getDesktopProperty("win.uiEffects.animationsEnabled") as? Boolean)?.not()
    }.getOrNull() ?: false
}

/**
 * Reusable collapsible-container wrapper: horizontally expands/shrinks
 * [content] based on [visible], honouring [reducedMotion] by skipping the
 * animation entirely rather than shortening it (a skipped transition, not a
 * fast one, is what "reduced motion" means).
 */
@Composable
internal fun DesktopCollapsibleSidebar(
    visible: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = if (reducedMotion) EnterTransition.None else expandHorizontally(),
        exit = if (reducedMotion) ExitTransition.None else shrinkHorizontally(),
        modifier = modifier,
    ) {
        Row(Modifier.fillMaxHeight()) {
            content()
        }
    }
}

private const val TooltipShowDelayMs = 150L

/**
 * Minimal hover tooltip built only on plain Compose + Material3 primitives —
 * deliberately NOT [DesktopTooltip] (which renders through Jewel's
 * `TooltipChip`/`JewelLocalContentColor`): that path pulls in a Jewel class
 * file built for a newer JDK than this module's JVM 21 test toolchain can
 * load (`JewelThemeKt`, class file version 69 vs. the toolchain's 65),
 * so any test that renders it fails with `UnsupportedClassVersionError`
 * regardless of what the test asserts. Runtime is unaffected — the
 * application launches on JDK 26 — but new sidebar-chrome tests need to run
 * under the JVM 21 test toolchain, so this avoids the Jewel dependency.
 */
@Composable
private fun DesktopChromeTooltip(
    text: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(hovered) {
        if (hovered) {
            delay(TooltipShowDelayMs.milliseconds)
            show = true
        } else {
            show = false
        }
    }
    Box(modifier = modifier.hoverable(interaction)) {
        content()
        if (show) {
            Popup(alignment = Alignment.BottomStart, properties = PopupProperties(focusable = false)) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHighest,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    shadowElevation = 6.dp,
                ) {
                    Text(
                        text = text,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

/**
 * Sidebar collapse/expand toggle, always present in the desktop chrome (AC
 * #1) regardless of sidebar state — this is the one control that survives
 * collapse, so it also anchors focus restoration (AC #7): the caller passes a
 * [FocusRequester] and requests focus onto this button right after the
 * sidebar closes.
 */
@Composable
internal fun DesktopSidebarToggleButton(
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val label = if (collapsed) "Show sidebar (Ctrl+B)" else "Hide sidebar (Ctrl+B)"
    DesktopChromeTooltip(text = label, modifier = modifier) {
        IconButton(
            onClick = onToggle,
            modifier = Modifier
                .let { m -> if (focusRequester != null) m.focusRequester(focusRequester) else m }
                .testTag(SidebarToggleTestTag)
                .semantics { contentDescription = label },
        ) {
            Icon(
                // Panel glyph (rounded rectangle + vertical divider), not a
                // hamburger — mirrors the reference desktop chrome.
                imageVector = if (collapsed) LettaIcons.PanelLeftOpen else LettaIcons.PanelLeftClose,
                contentDescription = null,
                // Explicit theme-aware tint — never rely on the ambient
                // content color for chrome icons: it flips light/dark with
                // the theme (onSurfaceVariant), unlike a hardcoded color.
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Overflow entry point for the sections that live inside the collapsible
 * sidebar (Memory/Schedules/Channels/Skills/New chat) — kept reachable per AC
 * #6 without reopening the sidebar. Conversation history itself is reachable
 * through the existing command palette (Ctrl+K), which already lists every
 * conversation.
 */
@Composable
internal fun DesktopSidebarOverflowMenu(
    mode: WorkPlayMode,
    onNewChat: () -> Unit,
    onDestination: (LensDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = "More (${WorkPlayLens.destinationLabel(mode, LensDestination.Memory)}, " +
        "${WorkPlayLens.destinationLabel(mode, LensDestination.Schedules)}, and more)"
    DesktopChromeTooltip(text = "More", modifier = modifier) {
        IconButton(
            onClick = { expanded = true },
            modifier = Modifier.semantics { contentDescription = label },
        ) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DropdownMenuItem(
            text = { Text("New chat") },
            leadingIcon = { Icon(Icons.Outlined.Add, contentDescription = null) },
            onClick = { expanded = false; onNewChat() },
        )
        DropdownMenuItem(
            text = { Text(WorkPlayLens.destinationLabel(mode, LensDestination.Memory)) },
            leadingIcon = { Icon(Icons.Outlined.Psychology, contentDescription = null) },
            onClick = { expanded = false; onDestination(LensDestination.Memory) },
        )
        DropdownMenuItem(
            text = { Text(WorkPlayLens.destinationLabel(mode, LensDestination.Schedules)) },
            leadingIcon = { Icon(Icons.Outlined.Schedule, contentDescription = null) },
            onClick = { expanded = false; onDestination(LensDestination.Schedules) },
        )
        DropdownMenuItem(
            text = { Text(WorkPlayLens.destinationLabel(mode, LensDestination.Channels)) },
            leadingIcon = { Icon(Icons.Outlined.Hub, contentDescription = null) },
            onClick = { expanded = false; onDestination(LensDestination.Channels) },
        )
        DropdownMenuItem(
            text = { Text(WorkPlayLens.destinationLabel(mode, LensDestination.Skills)) },
            leadingIcon = { Icon(Icons.Outlined.Build, contentDescription = null) },
            onClick = { expanded = false; onDestination(LensDestination.Skills) },
        )
    }
}

/** Global Ctrl/Cmd+B shortcut for the sidebar toggle, documented alongside Ctrl/Cmd+K for the palette. */
@Composable
internal fun SidebarToggleKeyDispatcherEffect(onToggle: () -> Unit) {
    DisposableEffect(onToggle) {
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        val dispatcher = KeyEventDispatcher { event ->
            if (isSidebarToggleKey(event)) {
                onToggle()
                true
            } else {
                false
            }
        }
        focusManager.addKeyEventDispatcher(dispatcher)
        onDispose { focusManager.removeKeyEventDispatcher(dispatcher) }
    }
}

private fun isSidebarToggleKey(event: KeyEvent): Boolean =
    event.id == KeyEvent.KEY_PRESSED &&
        event.keyCode == KeyEvent.VK_B &&
        (event.isControlDown || event.isMetaDown)
