package com.letta.mobile.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.letta.mobile.data.search.CommandPalette
import com.letta.mobile.data.search.PaletteItem
import com.letta.mobile.data.search.PaletteItemKind
import com.letta.mobile.desktop.chat.AgentOrb
import dev.nucleusframework.core.runtime.Platform
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.jewel.ui.component.TextField as JewelTextField

/** Actions the quick-query window routes back into the main app. */
internal data class DesktopQuickQueryActions(
    /** Open a palette item (agent/conversation/page) in the main window. */
    val onSelectItem: (PaletteItem) -> Unit,
    /** Send free-typed text to the current agent; second arg is ambient context. */
    val onSubmitPrompt: (String, String?) -> Unit,
)

/**
 * Bridge between the main window's composition (which owns the controllers
 * and palette data) and the application-scoped Spotlight-style quick-query
 * window. The main app publishes items/actions; the hotkey opens the window.
 */
internal class DesktopQuickQueryCoordinator {
    val visible = MutableStateFlow(false)
    val ambientContext = MutableStateFlow<String?>(null)
    val items = MutableStateFlow<List<PaletteItem>>(emptyList())
    val actions = MutableStateFlow<DesktopQuickQueryActions?>(null)

    /**
     * Capture the foreground-window context BEFORE our window takes focus,
     * then show. Called from the global-hotkey path while the user's current
     * application is still frontmost.
     */
    fun open() {
        ambientContext.value = captureForegroundWindowTitle()
        visible.value = true
    }

    fun close() {
        visible.value = false
    }
}

/**
 * Title of the OS foreground window (e.g. "AppNavGraph.kt — VS Code"), used
 * as ambient context for quick queries. Captured only at the moment the user
 * invokes the quick-query hotkey — never monitored continuously. Windows-only
 * for now (JNA user32); other platforms return null.
 */
internal fun captureForegroundWindowTitle(): String? {
    if (Platform.Current != Platform.Windows) return null
    return runCatching {
        val user32 = com.sun.jna.platform.win32.User32.INSTANCE
        val hwnd = user32.GetForegroundWindow() ?: return null
        val buffer = CharArray(FOREGROUND_TITLE_MAX_CHARS)
        val length = user32.GetWindowText(hwnd, buffer, buffer.size)
        if (length <= 0) null else String(buffer, 0, length).trim().takeIf { it.isNotEmpty() }
    }.getOrNull()
}

private const val FOREGROUND_TITLE_MAX_CHARS = 1024

/** Prefix a free-typed prompt with the ambient window context, when kept. */
internal fun quickQueryPrompt(text: String, ambientContext: String?): String {
    val trimmed = text.trim()
    if (ambientContext.isNullOrBlank()) return trimmed
    return "[Context: the user is currently looking at \"$ambientContext\"]\n\n$trimmed"
}

/**
 * Spotlight/Raycast-style floating query bar: frameless, transparent,
 * always-on-top, centered. Search results open in the main window; free text
 * goes to the current agent (Ctrl+Enter, or Enter with no matches). Dismisses
 * on Esc or focus loss.
 */
@Composable
internal fun DesktopQuickQueryWindow(coordinator: DesktopQuickQueryCoordinator) {
    val visible by coordinator.visible.collectAsState()
    if (!visible) return
    val state = rememberWindowState(
        width = 680.dp,
        height = 440.dp,
        position = WindowPosition(Alignment.Center),
    )
    Window(
        onCloseRequest = coordinator::close,
        state = state,
        undecorated = true,
        transparent = true,
        resizable = false,
        alwaysOnTop = true,
        title = "Letta Quick Query",
    ) {
        DisposableEffect(window) {
            // Close on click-away — but only once the window has actually held
            // focus. Windows' foreground-lock can deny focus to a window opened
            // from a background process; a naive lost-focus close then fires
            // before the bar even paints and it appears to never open.
            var hadFocus = false
            val listener = object : WindowFocusListener {
                override fun windowGainedFocus(event: WindowEvent?) {
                    hadFocus = true
                }

                override fun windowLostFocus(event: WindowEvent?) {
                    if (hadFocus) coordinator.close()
                }
            }
            window.addWindowFocusListener(listener)
            // Best-effort focus grab; if the OS denies it the bar stays
            // visible (always-on-top) awaiting a click.
            window.toFront()
            window.requestFocus()
            onDispose { window.removeWindowFocusListener(listener) }
        }
        // Each Compose window is its own composition root: NO composition
        // locals from the main window propagate here. That means the theme
        // stack (Jewel's TextField throws "No TextFieldStyle provided"
        // otherwise) AND the crash-reporting exception handler must both be
        // re-provided, or an exception in this window kills the app with no
        // dialog and no crash-log entry.
        @OptIn(ExperimentalComposeUiApi::class)
        CompositionLocalProvider(
            LocalWindowExceptionHandlerFactory provides CrashReportingExceptionHandlerFactory,
        ) {
            DesktopJewelTheme {
                DesktopMaterialTheme {
                    QuickQueryContent(coordinator)
                }
            }
        }
    }
}

@Composable
private fun QuickQueryContent(coordinator: DesktopQuickQueryCoordinator) {
    var query by remember { mutableStateOf(TextFieldValue("")) }
    val items by coordinator.items.collectAsState()
    val actions by coordinator.actions.collectAsState()
    val ambient by coordinator.ambientContext.collectAsState()
    var includeContext by remember { mutableStateOf(true) }
    val groups = remember(items, query.text) { CommandPalette.grouped(items, query.text) }
    val flatResults = remember(groups) { groups.flatMap { it.second } }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun submitPrompt() {
        val text = query.text.trim()
        if (text.isEmpty()) return
        actions?.onSubmitPrompt(text, ambient.takeIf { includeContext })
        coordinator.close()
    }

    fun openTopResult() {
        val top = flatResults.firstOrNull() ?: return
        actions?.onSelectItem(top)
        coordinator.close()
    }

    Box(modifier = Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.TopCenter) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 12.dp,
        ) {
            Column {
                QuickQuerySearchRow(
                    query = query,
                    onQueryChange = { query = it },
                    focusRequester = focusRequester,
                    onKeyEvent = { event ->
                        handleQuickQueryKey(
                            event = event,
                            hasResults = flatResults.isNotEmpty(),
                            keys = QuickQueryKeyActions(
                                onClose = coordinator::close,
                                onOpenTop = ::openTopResult,
                                onSubmit = ::submitPrompt,
                            ),
                        )
                    },
                )
                val ambientTitle = ambient
                if (ambientTitle != null && includeContext) {
                    AmbientContextChip(title = ambientTitle, onDismiss = { includeContext = false })
                }
                QuickQueryDivider()
                QuickQueryResults(
                    query = query.text,
                    items = items,
                    groups = groups,
                    onSelect = { item ->
                        actions?.onSelectItem(item)
                        coordinator.close()
                    },
                )
                QuickQueryFooter()
            }
        }
    }
}

@Composable
private fun QuickQueryFooter() {
    QuickQueryDivider()
    Text(
        text = "⏎ open top result · Ctrl+⏎ ask current agent · Esc close",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

private const val QUICK_QUERY_RECENT_AGENTS_LIMIT = 9

@Composable
private fun QuickQuerySearchRow(
    query: TextFieldValue,
    onQueryChange: (TextFieldValue) -> Unit,
    focusRequester: FocusRequester,
    onKeyEvent: (androidx.compose.ui.input.key.KeyEvent) -> Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        JewelTextField(
            value = query,
            onValueChange = onQueryChange,
            // Borderless: the bar's Surface is the visual frame; Jewel's own
            // focus ring reads as a stray outline here.
            undecorated = true,
            placeholder = {
                // Explicit compact style: the M3 default leaks a larger size
                // into Jewel's text slot and clips the descenders.
                Text(
                    text = "Search agents, or ask anything…",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent(onKeyEvent),
        )
    }
}

private data class QuickQueryKeyActions(
    val onClose: () -> Unit,
    val onOpenTop: () -> Unit,
    val onSubmit: () -> Unit,
)

/** Escape closes; Ctrl+Enter always submits; Enter opens the top match or submits. */
private fun handleQuickQueryKey(
    event: androidx.compose.ui.input.key.KeyEvent,
    hasResults: Boolean,
    keys: QuickQueryKeyActions,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when {
        event.key == Key.Escape -> {
            keys.onClose()
            true
        }
        event.key == Key.Enter && event.isCtrlPressed -> {
            keys.onSubmit()
            true
        }
        event.key == Key.Enter -> {
            if (hasResults) keys.onOpenTop() else keys.onSubmit()
            true
        }
        else -> false
    }
}

@Composable
private fun QuickQueryResults(
    query: String,
    items: List<PaletteItem>,
    groups: List<Pair<PaletteItemKind, List<PaletteItem>>>,
    onSelect: (PaletteItem) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
        // Element-style "recently viewed" strip: the most recent agents as
        // avatar chips, pinned above the result groups.
        val recentAgents = items.filter { it.kind == PaletteItemKind.Agent }
            .take(QUICK_QUERY_RECENT_AGENTS_LIMIT)
        if (query.isBlank() && recentAgents.isNotEmpty()) {
            item(key = "qq-recent-agents") {
                RecentAgentsStrip(agents = recentAgents, onSelect = onSelect)
            }
        }
        groups.forEach { (kind, results) ->
            item(key = "qq-h-$kind") {
                Text(
                    text = CommandPalette.sectionTitle(kind).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
                )
            }
            items(results, key = { "qq-$kind-${it.id}" }) { item ->
                QuickQueryRow(item = item, onClick = { onSelect(item) })
            }
        }
    }
}

@Composable
private fun RecentAgentsStrip(
    agents: List<PaletteItem>,
    onSelect: (PaletteItem) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "RECENTLY VIEWED",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 6.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            agents.forEach { item ->
                Column(
                    modifier = Modifier
                        .clickable(onClick = { onSelect(item) })
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                        .width(64.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AgentOrb(index = item.orbIndex ?: 0, size = 44.dp, cornerRadius = 12.dp)
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickQueryDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun AmbientContextChip(title: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "Looking at: $title",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Remove context",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(14.dp).clickable(onClick = onDismiss),
                )
            }
        }
    }
}

@Composable
private fun QuickQueryRow(item: PaletteItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (item.kind) {
            PaletteItemKind.Destination -> Icon(
                imageVector = Icons.Outlined.ArrowForward,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            else -> AgentOrb(index = item.orbIndex ?: 0, size = 22.dp, cornerRadius = 6.dp)
        }
        Text(
            text = item.label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        item.sublabel?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}
