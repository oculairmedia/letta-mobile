package com.letta.mobile.ui.shell.palette

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.components.picker.LettaPickerContent
import com.letta.mobile.ui.components.picker.LettaPickerSpec
import com.letta.mobile.ui.components.picker.PickMode
import com.letta.mobile.ui.components.picker.PickerCallbacks
import com.letta.mobile.ui.components.picker.PickerItem
import com.letta.mobile.ui.components.picker.PickerSearch
import com.letta.mobile.ui.components.picker.PickerSlots
import com.letta.mobile.data.search.MessageSearchSelection
import com.letta.mobile.ui.host.LettaDialogFrame
import com.letta.mobile.ui.search.LettaSearchChipRow
import com.letta.mobile.ui.search.LettaSearchChipRows
import com.letta.mobile.ui.search.LettaSearchRow
import com.letta.mobile.ui.search.messageSearchChipRows
import com.letta.mobile.ui.shell.header.UnifiedSearchHit
import com.letta.mobile.ui.shell.header.UnifiedSearchSources
import com.letta.mobile.ui.host.PaneSize
import com.letta.mobile.ui.host.PaneSpec
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

/** What the quick-query bar routes back into the app. */
@Immutable
class QuickQueryActions(
    /** Opens a picked result (agent, conversation, message, page) in the main window. */
    val onOpen: (UnifiedSearchHit) -> Unit,
    /** Sends free text to the current agent, with the ambient context the user kept (or null). */
    val onSubmitPrompt: (String, String?) -> Unit,
    val onClose: () -> Unit,
)

/**
 * letta-mobile-ckmub.3.6: the Spotlight-style quick-query bar's body: the palette's [LettaPickerContent]
 * over the unified search (recent agents first, then the matches), the ambient-context chip under the search, and a key
 * hint. Enter opens the highlighted result or, with none, asks the current agent; Ctrl+Enter always
 * asks. [ambientContext] is already sanitized by the platform that captured it. The always-on-top
 * window around it stays the platform's.
 */
@Composable
fun QuickQueryPanel(sources: UnifiedSearchSources, ambientContext: String?, actions: QuickQueryActions) {
    var query by remember { mutableStateOf("") }
    var includeContext by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf(MessageSearchSelection()) }
    val sections = rememberPaletteSections(sources, query, QuickQueryRecent, message)
    val chips = messageSearchChipRows(sources.messageCapabilities, message) { message = it }
    val kept = ambientContext.takeIf { includeContext }
    val submit = {
        val text = query.trim()
        if (text.isNotEmpty()) {
            actions.onSubmitPrompt(text, kept)
            actions.onClose()
        }
    }
    LettaDialogFrame(
        spec = PaneSpec(onDismiss = actions.onClose, size = PaneSize(width = Dp.Infinity), modifier = QuickQueryInset),
        tag = CommandPaletteTags.QUICK_QUERY,
    ) {
        LettaPickerContent(
            spec = LettaPickerSpec(
                sections = sections,
                query = query,
                mode = PickMode.Command,
                filterLocally = false,
                search = PickerSearch(QUICK_QUERY_PLACEHOLDER),
                tags = PaletteTags,
            ),
            callbacks = PickerCallbacks(
                onQuery = { query = it },
                onPick = { picked ->
                    picked.hit()?.let(actions.onOpen)
                    actions.onClose()
                },
                onDismiss = actions.onClose,
                onKey = { event, highlighted -> handleQuickQueryKey(event, highlighted, submit) },
            ),
            slots = PickerSlots(
                leading = { PaletteRowLeading(it.value) },
                header = { QuickQueryHeader(chips, kept) { includeContext = false } },
                footer = { QuickQueryFooter() },
            ),
        )
    }
}

@Composable
private fun QuickQueryHeader(chips: List<LettaSearchChipRow>, ambient: String?, onDropAmbient: () -> Unit) {
    LettaSearchChipRows(chips)
    ambient?.let { AmbientContextChip(it, onDismiss = onDropAmbient) }
}

/** Ctrl+Enter always asks the agent; Enter asks it only when no result is highlighted to open. */
private fun handleQuickQueryKey(event: KeyEvent, highlighted: PickerItem<LettaSearchRow>?, submit: () -> Unit): Boolean {
    if (!event.isEnterDown()) return false
    val asksAgent = event.isCtrlPressed || highlighted == null
    if (asksAgent) submit()
    return asksAgent
}

private fun KeyEvent.isEnterDown(): Boolean = type == KeyEventType.KeyDown && key in ENTER_KEYS

private val ENTER_KEYS = setOf(Key.Enter, Key.NumPadEnter)

@Composable
private fun QuickQueryFooter() {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Text(
        text = QUICK_QUERY_HINT,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm),
    )
}

@Composable
private fun AmbientContextChip(title: String, onDismiss: () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm).testTag(CommandPaletteTags.AMBIENT),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
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
                imageVector = LettaIcons.Clear,
                contentDescription = "Remove context",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(LettaDimens.Control.icon).clickable(onClick = onDismiss),
            )
        }
    }
}

/** Full width, inset from the platform window's edge so the frame's corners and shadow show. */
private val QuickQueryInset = Modifier.fillMaxWidth().padding(LettaDimens.Space.sm)

private val QuickQueryRecent = PaletteRecent(title = "Recently viewed", limit = 9)
private const val QUICK_QUERY_PLACEHOLDER = "Search agents, or ask anything…"
private const val QUICK_QUERY_HINT = "⏎ open result · Ctrl+⏎ ask current agent · Esc close"
