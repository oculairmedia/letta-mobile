package com.letta.mobile.ui.search

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp

/**
 * Presentation-neutral models for [LettaSearch].
 *
 * The point of this package is that a search surface differs from another
 * search surface only in *where it is drawn* and *which parts are switched on*
 * — never in how it filters, lays out rows, or handles the keyboard. Callers
 * map their own domain type into [LettaSearchRow] and pick a presentation;
 * everything below that is shared.
 *
 * Filtering itself is not here. `com.letta.mobile.data.search.TextMatch` and
 * `CommandPalette` in sharedLogic already do it, platform-neutrally, and this
 * package deliberately does not grow a second implementation.
 */

/** What to draw at the start of a row. */
@Immutable
sealed interface LettaSearchLeading {
    /** No leading element; the label starts at the row inset. */
    data object None : LettaSearchLeading

    /**
     * An agent orb. [agentId] lets the host draw the live mascot when one is
     * registered; [orbIndex] is the gradient fallback.
     */
    @Immutable
    data class Orb(val orbIndex: Int, val agentId: String? = null) : LettaSearchLeading

    @Immutable
    data class Icon(val image: ImageVector) : LettaSearchLeading
}

/**
 * One result.
 *
 * [meta] is trailing, right-aligned — a relative timestamp or a count. It is
 * separate from [sublabel] because it aligns to the row's end rather than
 * flowing under the label.
 */
@Immutable
data class LettaSearchRow(
    val id: String,
    val label: String,
    val sublabel: String? = null,
    val meta: String? = null,
    val leading: LettaSearchLeading = LettaSearchLeading.None,
)

/**
 * A titled group of rows. A section with a blank [title] renders its rows with
 * no header, which is how flat surfaces (the tab picker) use the same code as
 * sectioned ones (the command palette).
 */
@Immutable
data class LettaSearchSection(
    val title: String,
    val rows: List<LettaSearchRow>,
)

/**
 * Where an anchored panel sits relative to the trigger that opened it.
 *
 * [Overlay] is the default and exists to stop the trigger and the panel's own
 * field showing the same text twice: the panel covers the trigger, so its field
 * lands exactly where the trigger was and the two read as one control that
 * expanded. [Below] keeps the trigger visible and drops the panel under it,
 * for hosts where the trigger carries something the panel does not repeat.
 */
enum class LettaSearchAnchorMode { Overlay, Below }

/** One of the scope tabs across the top ("All", "Messages", "Conversations"). */
@Immutable
data class LettaSearchScope(
    val id: String,
    val label: String,
)

/** An optional checkbox beside the scope tabs, e.g. "Filter to this agent". */
@Immutable
data class LettaSearchToggle(
    val label: String,
    val checked: Boolean,
    val onCheckedChange: (Boolean) -> Unit,
)

/**
 * A pinned row above the results — "Create new agent", "New canvas",
 * "Create \"foo\" Bot". Actions are always visible; they are not filtered by
 * the query, because they are what the user does when the query finds nothing.
 *
 * [labelForQuery] receives the live query, so an action can name it
 * ("Create \"meri\"").
 */
@Immutable
data class LettaSearchAction(
    val id: String,
    val labelForQuery: (String) -> String,
    val icon: ImageVector? = null,
    val onInvoke: (String) -> Unit,
)

/**
 * Which parts of the search are switched on.
 *
 * Every field has a default that yields the simplest useful search — a field
 * and a list — so a caller only names what it actually wants. This is the
 * "flags on what you want it to display" contract: a new surface should be a
 * config, not a new composable.
 */
@Immutable
data class LettaSearchConfig(
    /** Heading above the field, e.g. "New conversation". Null draws none. */
    val title: String? = null,
    /**
     * Static label before the field, e.g. "To:". An address-line affordance:
     * it frames what the field accepts without spending a bordered input on it.
     */
    val fieldPrefix: String? = null,
    val placeholder: String = "Search",
    /**
     * A horizontal strip above the results — recently used entries, shown as
     * orb-over-name tiles. Hidden once a query is typed, since at that point
     * the results themselves are the answer.
     */
    val recents: List<LettaSearchRow> = emptyList(),
    val recentsTitle: String = "Recent",
    /** Scope tabs. Empty (the default) hides the row entirely. */
    val scopes: List<LettaSearchScope> = emptyList(),
    val selectedScopeId: String? = null,
    val onScopeSelected: (String) -> Unit = {},
    /** Checkbox beside the scopes; null hides it. */
    val toggle: LettaSearchToggle? = null,
    /** Pinned, unfiltered actions above the results. */
    val actions: List<LettaSearchAction> = emptyList(),
    /** Where an anchored panel sits relative to its trigger. */
    val anchorMode: LettaSearchAnchorMode = LettaSearchAnchorMode.Overlay,
    /** Section headers. Off for flat lists even when sections are supplied. */
    val showSectionHeaders: Boolean = true,
    /** Focus the field when the surface appears. */
    val autoFocus: Boolean = true,
    /** Caps the results list; the field, scopes and actions stay pinned. */
    val maxResultsHeight: Dp? = null,
    /**
     * Whether results render lazily.
     *
     * MUST be false inside a `DropdownMenu`: a menu sizes its content by
     * intrinsic width, and lazy layouts refuse to answer an intrinsic
     * measurement, so a LazyColumn inside one collapses. [LettaSearchDropdownContent]
     * sets this for you. Every other host should leave it true — the command
     * palette and Home both list hundreds of rows.
     */
    val lazyResults: Boolean = true,
    val emptyText: (String) -> String = { query ->
        if (query.isBlank()) "Nothing to show" else "No results for \"$query\""
    },
)
