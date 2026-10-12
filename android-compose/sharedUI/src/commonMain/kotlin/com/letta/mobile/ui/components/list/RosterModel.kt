package com.letta.mobile.ui.components.list

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.letta.mobile.ui.components.LettaMenuItem

/**
 * letta-mobile-ckmub.3.10 (hard cut S7, primitive P4): one titled group of a [RosterList]. A null
 * [title] draws no header; [emptyHint] is the quiet line an empty section shows (null: nothing).
 * [headerEnd] is drawn at the header's end (a count, a live dot).
 */
@Immutable
data class RosterSection<T>(
    val key: String,
    val items: List<T>,
    val title: String? = null,
    val emptyHint: String? = null,
    val headerEnd: (@Composable () -> Unit)? = null,
)

/** Ways to build a roster's sections. */
object RosterSections {
    const val PINNED_TITLE = "Pinned"

    /**
     * [items] as a pinned group under [pinnedTitle] (left out when nothing is pinned), then the
     * rest under [title]. Each group keeps the order it had in [items].
     */
    fun <T> pinnedFirst(
        key: String,
        items: List<T>,
        isPinned: (T) -> Boolean,
        title: String? = null,
        pinnedTitle: String = PINNED_TITLE,
        emptyHint: String? = null,
    ): List<RosterSection<T>> {
        val (pinned, rest) = items.partition(isPinned)
        val pinnedSection = RosterSection("$key-pinned", pinned, title = pinnedTitle).takeIf { pinned.isNotEmpty() }
        return listOfNotNull(pinnedSection, RosterSection(key, rest, title = title, emptyHint = emptyHint))
    }
}

/** Which rows read selected, and what choosing one (a click, Enter on the keyboard cursor) does. */
@Immutable
class RosterSelection<T>(
    val isSelected: (T) -> Boolean = { false },
    val onSelect: (T) -> Unit = {},
)

/** A row action beyond opening it: a hover button on pointer, a menu entry on touch, or the swipe. */
@Immutable
data class RosterAction(
    val label: String,
    val icon: ImageVector? = null,
    val onClick: () -> Unit,
)

/**
 * Inline rename. [menuLabel] adds a rename entry to the row menu; null leaves the entry to the
 * caller's own menu (which calls [RosterRowState.startRename]). F2 on the keyboard cursor starts it.
 */
@Immutable
class RosterRename<T>(
    val titleOf: (T) -> String,
    val onRename: (item: T, title: String) -> Unit,
    val menuLabel: String? = "Rename",
    val placeholder: String = "Name",
    val fieldTag: String = RosterListTags.RENAME_FIELD,
    /** Which items take a new name (a list mixing chats and canvases renames only the chats). */
    val canRename: (T) -> Boolean = { true },
)

/**
 * The reorder hook: [onMove] asks the host to move an item to [toIndex] within its section; the
 * host persists the order and hands back new sections. Alt+Up / Alt+Down on the keyboard cursor and
 * the row menu's Move up / Move down call it.
 */
@Immutable
class RosterReorder<T>(val onMove: (item: T, toIndex: Int) -> Unit)

/**
 * What a row offers. [menu] opens through the host's row menu (right-click on pointer, long-press
 * on touch). [quick] actions are hover buttons on pointer and join the menu on touch; [swipe] is a
 * touch swipe from the end. A null rename or reorder is one the host does not offer.
 */
@Immutable
class RosterActions<T>(
    val menu: (item: T, row: RosterRowState) -> List<LettaMenuItem> = { _, _ -> emptyList() },
    val quick: (T) -> List<RosterAction> = { emptyList() },
    val swipe: (T) -> RosterAction? = { null },
    val rename: RosterRename<T>? = null,
    val reorder: RosterReorder<T>? = null,
)

/**
 * What a [RosterList] shows and offers: its [sections] (each item keyed by [key], unique within
 * its section), which rows are selected, the row actions, how it is drawn, and what an empty list
 * shows ([empty]; null keeps the sections' own hints).
 */
@Immutable
class RosterSpec<T>(
    val sections: List<RosterSection<T>>,
    val key: (T) -> String,
    val selection: RosterSelection<T> = RosterSelection(),
    val actions: RosterActions<T> = RosterActions(),
    val options: RosterOptions = RosterOptions(),
    val empty: (@Composable () -> Unit)? = null,
)

/** The optional parts of a [RosterListRow]: drawn where the icon goes, and after the trailing label. */
@Immutable
class RosterRowSlots(
    val leading: (@Composable () -> Unit)? = null,
    val end: (@Composable () -> Unit)? = null,
)

/** An open rename: the current [title], what saving and closing do, and the empty field's [placeholder]. */
@Immutable
class RosterRenameRequest(
    val title: String,
    val onRename: (String) -> Unit,
    val onDone: () -> Unit,
    val placeholder: String = "Name",
)

/** How the list is drawn: its loading state, its root tag (rows and headers are tagged under it). */
@Immutable
data class RosterOptions(
    /** Shows a progress bar while every section is still empty. */
    val loading: Boolean = false,
    val testTag: String = RosterListTags.LIST,
)

/** What one row is when its content draws: selection, the keyboard cursor, hover, and its click. */
@Immutable
class RosterRowState(
    val selected: Boolean = false,
    /** The keyboard cursor is on this row. */
    val highlighted: Boolean = false,
    val hovered: Boolean = false,
    val onClick: () -> Unit = {},
    /** Opens the inline rename field; null when the roster offers no rename. */
    val startRename: (() -> Unit)? = null,
    val interaction: MutableInteractionSource? = null,
)

/** The list's own state: its scroll, the keyboard cursor and the row being renamed (both by row key). */
@Stable
class RosterListState(val lazy: LazyListState = LazyListState()) {
    var cursor by mutableStateOf<String?>(null)
        internal set
    var renaming by mutableStateOf<String?>(null)
        internal set

    fun startRename(rowKey: String) {
        renaming = rowKey
    }

    fun stopRename() {
        renaming = null
    }
}

@Composable
fun rememberRosterListState(): RosterListState {
    val lazy = rememberLazyListState()
    return remember(lazy) { RosterListState(lazy) }
}

/** Test tags; a list's rows, headers and states are tagged under its [RosterOptions.testTag]. */
object RosterListTags {
    const val LIST = "roster-list"
    const val RENAME_FIELD = "roster-rename"

    fun row(list: String, key: String) = "$list-row-$key"
    fun header(list: String, section: String) = "$list-section-$section"
    fun empty(list: String) = "$list-empty"
    fun loading(list: String) = "$list-loading"
    /** A hover button of the row tagged [rowTag]. */
    fun quick(rowTag: String, label: String) = "$rowTag-quick-$label"

    /** The swipe action shown behind the row tagged [rowTag]. */
    fun swipeBackdrop(rowTag: String) = "$rowTag-swipe"
}
