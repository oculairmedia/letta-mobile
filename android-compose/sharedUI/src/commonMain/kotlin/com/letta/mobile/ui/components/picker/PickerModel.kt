package com.letta.mobile.ui.components.picker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import com.letta.mobile.data.search.TextMatch

/*
 * letta-mobile-ckmub.3.6 (hard cut S8, primitive P5): the data a [LettaPicker] draws. Everything here
 * is plain state, so the filtering, flattening and keyboard rules are testable without a composition.
 */

/** How a pick behaves: one value, a set, or a command run at once (the palette). */
enum class PickMode { Single, Multi, Command }

/** One row. [key] is unique across the whole picker; [value] is what a pick hands back. */
@Immutable
data class PickerItem<out T>(
    val key: String,
    val value: T,
    val title: String,
    val subtitle: String? = null,
    /** A quiet label at the row's end (a time, a provider, a count). */
    val trailing: String? = null,
    val icon: ImageVector? = null,
    /** Extra text the search matches besides the title and subtitle. */
    val keywords: String? = null,
    /** Opens a second level instead of choosing a value: the row ends in a chevron. */
    val drillIn: Boolean = false,
    /** Tags the row, so tests find it whichever section it sits in. */
    val testTag: String? = null,
)

/** A titled group of rows. */
@Immutable
data class PickerSection<out T>(
    val key: String,
    val title: String?,
    val items: List<PickerItem<T>>,
    /** Null: the section cannot fold. Otherwise whether it is folded now. */
    val collapsed: Boolean? = null,
    /** False: the search leaves this section as it is (a path list beside a searchable one). */
    val searchable: Boolean = true,
    /** False: the section hides while a query is typed (Recent). */
    val showWhileSearching: Boolean = true,
) {
    companion object {
        /** The recently used rows: first in the list, gone once the user types. */
        fun <T> recent(title: String, items: List<PickerItem<T>>, key: String = "recent"): PickerSection<T> =
            PickerSection(key, title, items, showWhileSearching = false)
    }
}

/** Whether the rows are known yet. A failure keeps showing the rows it has, with the message under them. */
@Immutable
sealed interface PickerLoad {
    data object Ready : PickerLoad

    data object Loading : PickerLoad

    data class Failed(val message: String) : PickerLoad
}

/** What an empty list says: a line, and an optional hint under it. */
@Immutable
data class PickerEmpty(val title: String, val hint: String? = null)

/** The second level a picker is showing: its title over a back arrow. */
@Immutable
class PickerLevel(val title: String, val onBack: () -> Unit)

/** Test tags a surface can keep from before it composed the picker. */
@Immutable
data class PickerTags(
    val root: String = LettaPickerTags.ROOT,
    val search: String = LettaPickerTags.SEARCH,
    val list: String = LettaPickerTags.LIST,
    val sectionPrefix: String = LettaPickerTags.SECTION_PREFIX,
    val rowPrefix: String = LettaPickerTags.ROW_PREFIX,
    val empty: String = LettaPickerTags.EMPTY,
    val error: String = LettaPickerTags.ERROR,
)

object LettaPickerTags {
    const val ROOT = "letta-picker"
    const val SEARCH = "letta-picker-search"
    const val LIST = "letta-picker-list"
    const val SECTION_PREFIX = "letta-picker-section-"
    const val ROW_PREFIX = "letta-picker-row-"
    const val EMPTY = "letta-picker-empty"
    const val ERROR = "letta-picker-error"
    const val LOADING = "letta-picker-loading"
    const val BACK = "letta-picker-back"
}

/** One line of the flattened list: a section header, or a row with its place among the rows. */
@Immutable
sealed interface PickerLine<out T> {
    val key: String

    data class Header<T>(val section: PickerSection<T>) : PickerLine<T> {
        override val key: String get() = "section-${section.key}"
    }

    data class Row<T>(val item: PickerItem<T>, val index: Int) : PickerLine<T> {
        override val key: String get() = "row-${item.key}"
    }
}

/** The sections after the search, flattened into lines; [rows] are the rows keys move through. */
@Immutable
class PickerLayout<out T>(val lines: List<PickerLine<T>>, val rows: List<PickerItem<T>>) {
    private val lineOfRow: IntArray = lines.withIndex()
        .filter { it.value is PickerLine.Row<*> }
        .map { it.index }
        .toIntArray()

    /** The list position of row [rowIndex], for scrolling it into view. */
    fun lineOf(rowIndex: Int): Int = lineOfRow.getOrElse(rowIndex) { 0 }

    val isEmpty: Boolean get() = rows.isEmpty()

    companion object {
        /**
         * The visible lines for [query]. With [filterLocally] the picker matches each searchable
         * section's rows itself; without it the caller already filtered (a controller that searches
         * a server). A section with no rows left drops out with its header.
         */
        fun <T> of(sections: List<PickerSection<T>>, query: String, filterLocally: Boolean = true): PickerLayout<T> {
            val search = PickerQuery(query, filterLocally)
            val lines = mutableListOf<PickerLine<T>>()
            val rows = mutableListOf<PickerItem<T>>()
            sections.mapNotNull(search::apply).forEach { section ->
                if (section.title != null) lines += PickerLine.Header(section)
                if (section.collapsed != true) {
                    section.items.forEach { item ->
                        lines += PickerLine.Row(item, rows.size)
                        rows += item
                    }
                }
            }
            return PickerLayout(lines, rows)
        }
    }
}

/** A typed query and whether the picker matches rows itself. */
private class PickerQuery(private val query: String, private val filterLocally: Boolean) {
    private val searching = query.isNotBlank()
    private val filtering = searching && filterLocally

    /** The section as the search leaves it, or null when it has nothing left to show. */
    fun <T> apply(section: PickerSection<T>): PickerSection<T>? {
        if (searching && !section.showWhileSearching) return null
        val items = if (filters(section)) section.items.filter(::matches) else section.items
        return section.copy(items = items).takeIf { items.isNotEmpty() }
    }

    private fun filters(section: PickerSection<*>): Boolean = filtering && section.searchable

    private fun matches(item: PickerItem<*>): Boolean = TextMatch.matches(query, item.title, item.subtitle, item.keywords)
}

/** The keyboard's rules: which row is highlighted as the user moves, and where it starts. */
object PickerKeys {
    /** No row highlighted: Enter does nothing until an arrow key moves into the list. */
    const val NONE = -1

    /** A palette starts on its top row (Enter runs it); a typed query highlights the best match. */
    fun initial(mode: PickMode, query: String, rowCount: Int): Int =
        if (rowCount > 0 && (mode == PickMode.Command || query.isNotBlank())) 0 else NONE

    /** One step up or down, stopping at the ends rather than wrapping. */
    fun move(current: Int, delta: Int, rowCount: Int): Int {
        if (rowCount <= 0) return NONE
        if (current == NONE) return if (delta > 0) 0 else rowCount - 1
        return (current + delta).coerceIn(0, rowCount - 1)
    }
}

/**
 * The levels of a drill-in picker (root list, then a second-level list with a back arrow). The pane
 * that holds the picker asks [back] first when it is dismissed, so Escape, the system back and a
 * scrim tap leave the second level before they close the pane.
 */
@Stable
class PickerDrillIn<L : Any>(private val root: L) {
    private val stack = mutableStateListOf<L>()

    val current: L get() = stack.lastOrNull() ?: root

    val atRoot: Boolean get() = stack.isEmpty()

    fun open(level: L) {
        stack += level
    }

    /** Leaves the current level; false at the root, where the pane itself should close. */
    fun back(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }
}

@Composable
fun <L : Any> rememberPickerDrillIn(root: L): PickerDrillIn<L> = remember { PickerDrillIn(root) }
