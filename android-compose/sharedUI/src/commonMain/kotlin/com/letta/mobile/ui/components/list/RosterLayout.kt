package com.letta.mobile.ui.components.list

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/** One line of a roster: a section header, a row, or a section's empty hint. */
internal sealed interface RosterLine<out T> {
    val key: String

    data class Header<T>(val section: RosterSection<T>) : RosterLine<T> {
        override val key: String get() = "section:${section.key}"
    }

    data class Row<T>(
        val item: T,
        override val key: String,
        val sectionKey: String,
        /** The row's place in its own section (what a reorder moves). */
        val indexInSection: Int,
        val sectionSize: Int,
    ) : RosterLine<T>

    data class Hint(val sectionKey: String, val text: String) : RosterLine<Nothing> {
        override val key: String get() = "hint:$sectionKey"
    }
}

/** The sections flattened into what the lazy list draws, plus the rows alone (the keyboard's order). */
internal class RosterLayout<T> private constructor(val lines: List<RosterLine<T>>) {
    val rows: List<RosterLine.Row<T>> = lines.filterIsInstance<RosterLine.Row<T>>()
    val isEmpty: Boolean get() = rows.isEmpty()

    fun rowIndex(rowKey: String?): Int = rows.indexOfFirst { it.key == rowKey }

    fun lineOf(rowKey: String): Int = lines.indexOfFirst { it.key == rowKey }

    companion object {
        /** A row's key: unique across sections, since one item may sit in two (a pin and its group). */
        fun rowKey(sectionKey: String, itemKey: String): String = "$sectionKey:$itemKey"

        fun <T> of(sections: List<RosterSection<T>>, key: (T) -> String): RosterLayout<T> = RosterLayout(
            buildList {
                sections.forEach { section ->
                    if (section.title != null) add(RosterLine.Header(section))
                    section.items.forEachIndexed { index, item ->
                        add(RosterLine.Row(item, rowKey(section.key, key(item)), section.key, index, section.items.size))
                    }
                    if (section.items.isEmpty()) section.emptyHint?.let { add(RosterLine.Hint(section.key, it)) }
                }
            },
        )
    }
}

/** What a key press on the list asks for. */
internal sealed interface RosterKeyCommand {
    data class MoveCursor(val delta: Int) : RosterKeyCommand
    data object Activate : RosterKeyCommand
    data object Rename : RosterKeyCommand
    data class Reorder(val delta: Int) : RosterKeyCommand
    data object ClearCursor : RosterKeyCommand
}

/** The roster's keyboard: arrows move the cursor, Enter opens, F2 renames, Alt+arrows reorder, Escape clears. */
internal object RosterKeys {
    fun command(event: KeyEvent): RosterKeyCommand? {
        if (event.type != KeyEventType.KeyDown) return null
        val vertical = when (event.key) {
            Key.DirectionDown -> 1
            Key.DirectionUp -> -1
            else -> 0
        }
        return when {
            vertical != 0 && event.isAltPressed -> RosterKeyCommand.Reorder(vertical)
            vertical != 0 -> RosterKeyCommand.MoveCursor(vertical)
            event.key == Key.Enter || event.key == Key.NumPadEnter -> RosterKeyCommand.Activate
            event.key == Key.F2 -> RosterKeyCommand.Rename
            event.key == Key.Escape -> RosterKeyCommand.ClearCursor
            else -> null
        }
    }

    /** The cursor after a move of [delta] from [current] over [size] rows; it stops at the ends. */
    fun move(current: Int, delta: Int, size: Int): Int = when {
        size == 0 -> -1
        current < 0 -> if (delta > 0) 0 else size - 1
        else -> (current + delta).coerceIn(0, size - 1)
    }

    /** Where a reorder by [delta] puts a row, or null when it is already at that end of its section. */
    fun reorderTarget(row: RosterLine.Row<*>, delta: Int): Int? =
        (row.indexInSection + delta).takeIf { it in 0 until row.sectionSize }
}
