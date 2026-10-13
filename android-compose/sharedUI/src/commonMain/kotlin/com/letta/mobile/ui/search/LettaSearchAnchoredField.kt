package com.letta.mobile.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.host.LettaPane
import com.letta.mobile.ui.host.LettaPaneKind
import com.letta.mobile.ui.host.PaneAnchor
import com.letta.mobile.ui.host.PaneSize
import com.letta.mobile.ui.theme.LettaDimens

/** What an anchored search searches: the live [query] and where edits, picks and dismissal go. */
@Immutable
class LettaSearchSource(
    val query: String,
    val onQueryChange: (String) -> Unit,
    val sections: List<LettaSearchSection>,
    val onRowSelected: (LettaSearchRow) -> Unit,
    val onDismiss: () -> Unit = {},
)

/** The anchored search's trigger: a field-shaped box (a pointer header) or a search icon (a phone's). */
enum class LettaSearchTriggerStyle { Field, Icon }

/**
 * A request from outside the field to open its pane (Android chat's `/search` command). The host
 * keeps one and calls [request]; the field that shows it takes the request once
 * (letta-mobile-zxk2n.39). A counter the field compared on every composition reopened the pane each
 * time the header came back (a field leaving composition forgets what it had seen); a taken request
 * is gone, so returning to the chat leaves the pane closed.
 */
@Stable
class LettaSearchOpenRequest {
    internal var pending: Boolean by mutableStateOf(false)
        private set

    fun request() {
        pending = true
    }

    /** True once per [request]: the caller opens the pane. */
    internal fun take(): Boolean {
        if (!pending) return false
        pending = false
        return true
    }
}

/** How the anchored search is opened: its trigger [style], and the host's [openRequest] (null: only the trigger opens it). */
@Immutable
data class LettaSearchAnchor(
    val style: LettaSearchTriggerStyle = LettaSearchTriggerStyle.Field,
    val openRequest: LettaSearchOpenRequest? = null,
)

/**
 * Anchored field: the header expression (the shell header's SearchSlot).
 *
 * The header box is a TRIGGER, not the input. Clicking it opens a
 * [LettaPaneKind.Popover] through the [com.letta.mobile.ui.host.LettaHost]
 * in effect (letta-mobile-ckmub.3.5), and the real field lives in there.
 *
 * That indirection is forced by the host, not preference. Nucleus's title bar
 * applies `focusProperties { canFocus = false }` to its whole subtree on
 * Windows and Linux, so Tab navigation cannot wander into the window-drag
 * region — which also means NOTHING inside it can take keyboard focus. In
 * Compose a parent's `canFocus = false` deactivates the subtree and a
 * descendant cannot re-enable it, so an inline field there can be clicked and
 * will never receive a keystroke. A popover composes in its own focusable
 * layer, outside that focus scope, so the field inside one focuses normally.
 *
 * The pane OVERLAYS the field-shaped trigger ([PaneAnchor.OverStart], at the
 * trigger's own width): its input sits exactly where the trigger was and the
 * scopes, filter and results hang directly beneath it, so the window shows ONE
 * search input, never the trigger's copy of the query above a second field
 * (letta-mobile-06whj; #1606 had this, S6's move to [LettaPane] lost it). The
 * trigger draws nothing while the pane covers it. Escape (the pane's own key
 * handling) or a click outside closes the pane and the trigger shows the live
 * query again.
 *
 * An [LettaSearchTriggerStyle.Icon] trigger has no field to overlay: the pane
 * opens below it, and its field is still the only one.
 *
 * It is the same [LettaSearchScaffold] as every other expression.
 */
@Composable
fun LettaSearchAnchoredField(
    source: LettaSearchSource,
    modifier: Modifier = Modifier,
    config: LettaSearchConfig = LettaSearchConfig(),
    anchor: LettaSearchAnchor = LettaSearchAnchor(),
) {
    var expanded by remember { mutableStateOf(false) }
    var triggerWidth by remember { mutableStateOf(Dp.Unspecified) }
    val density = LocalDensity.current
    val openRequest = anchor.openRequest
    LaunchedEffect(openRequest, openRequest?.pending) { if (openRequest?.take() == true) expanded = true }
    Box(modifier = modifier.onSizeChanged { triggerWidth = with(density) { it.width.toDp() } }) {
        val query = source.query
        when (anchor.style) {
            LettaSearchTriggerStyle.Icon -> LettaSearchIconTrigger(active = query.isNotBlank(), label = config.placeholder) { expanded = true }
            LettaSearchTriggerStyle.Field -> LettaSearchFieldTrigger(
                text = if (expanded) "" else query.ifBlank { config.placeholder },
                live = query.isNotBlank(),
                covered = expanded,
            ) { expanded = true }
        }
        if (expanded) {
            AnchoredSearchPane(source, config, AnchoredPlacement.of(anchor.style, triggerWidth)) { expanded = false }
        }
    }
}

/** Where the pane opens and how wide it is: over the field at its width, or below an icon at its own. */
@Immutable
internal class AnchoredPlacement private constructor(val anchor: PaneAnchor, val width: Dp) {
    /** The pane's content: the field's width when it covers the field, the panel's reading width otherwise. */
    fun contentModifier(): Modifier = if (width == Dp.Unspecified) {
        Modifier.widthIn(min = AnchoredPanelMinWidth, max = AnchoredPanelMaxWidth)
    } else {
        Modifier.fillMaxWidth()
    }

    companion object {
        fun of(style: LettaSearchTriggerStyle, triggerWidth: Dp): AnchoredPlacement =
            if (style == LettaSearchTriggerStyle.Field && triggerWidth != Dp.Unspecified) {
                AnchoredPlacement(PaneAnchor.OverStart, triggerWidth)
            } else {
                AnchoredPlacement(PaneAnchor.BelowStart, Dp.Unspecified)
            }
    }
}

@Composable
private fun AnchoredSearchPane(
    source: LettaSearchSource,
    config: LettaSearchConfig,
    placement: AnchoredPlacement,
    onClose: () -> Unit,
) {
    LettaPane(
        kind = LettaPaneKind.Popover(placement.anchor),
        onDismiss = {
            onClose()
            source.onDismiss()
        },
        size = PaneSize(width = placement.width, maxHeight = AnchoredPanelMaxPaneHeight),
        testTag = SearchPanelTestTag,
    ) {
        LettaSearchScaffold(
            query = source.query,
            onQueryChange = source.onQueryChange,
            sections = source.sections,
            onRowSelected = { row ->
                onClose()
                source.onRowSelected(row)
            },
            modifier = placement.contentModifier(),
            config = config.copy(autoFocus = true, maxResultsHeight = config.maxResultsHeight ?: AnchoredPanelMaxHeight),
            parts = if (config.showResults) LettaSearchParts.All else LettaSearchParts.FieldOnly,
        )
    }
}

/** The icon trigger: a search icon, tinted while a query is live. */
@Composable
private fun LettaSearchIconTrigger(active: Boolean, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.testTag(SearchTriggerTestTag)) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = label,
            tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The header's stand-in for the field: shows the live query (or the placeholder), opens the real
 * one. While [covered] (the pane's field sits on top of it) it keeps its size but draws nothing and
 * its [text] is empty, so the query is on screen once.
 */
@Composable
private fun LettaSearchFieldTrigger(text: String, live: Boolean, covered: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(SearchTriggerTestTag)
            .alpha(if (covered) 0f else 1f)
            .clip(RoundedCornerShape(LettaDimens.Radius.sm))
            .clickable(onClick = onClick)
            .padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(LettaDimens.Control.iconSm),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (live) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// Panel geometry: reading-width decisions, not points on the spacing scale.
private val AnchoredPanelMinWidth = 280.dp
private val AnchoredPanelMaxWidth = 380.dp
private val AnchoredPanelMaxHeight = 280.dp
private val AnchoredPanelMaxPaneHeight = 480.dp
