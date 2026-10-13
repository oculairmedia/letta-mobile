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

    /** A grid line: up to `columns` rows of one section side by side. */
    data class Cells<T>(val rows: List<Row<T>>) : RosterLine<T> {
        override val key: String get() = "cells:${rows.first().key}"
    }
}

/**
 * The sections flattened into what the lazy list draws, plus the rows alone (the keyboard's order).
 * With more than one column a section's rows are drawn [RosterLine.Cells] by [RosterLine.Cells].
 */
internal class RosterLayout<T> private constructor(
    private val sections: List<RosterSection<T>>,
    private val key: (T) -> String,
    private val columns: Int,
) {
    val lines: List<RosterLine<T>> = buildLines()
    val rows: List<RosterLine.Row<T>> = lines.flatMap { line ->
        when (line) {
            is RosterLine.Row -> listOf(line)
            is RosterLine.Cells -> line.rows
            else -> emptyList()
        }
    }
    val isEmpty: Boolean get() = rows.isEmpty()

    /** The rows' keys in order: two layouts with the same signature draw the same rows in the same places. */
    val signature: List<String> = rows.map { it.key }

    fun rowIndex(rowKey: String?): Int = rows.indexOfFirst { it.key == rowKey }

    /** The lazy line that draws [rowKey]: its own line, or the grid line holding it. */
    fun lineOf(rowKey: String): Int = lines.indexOfFirst { line ->
        line.key == rowKey || (line is RosterLine.Cells && line.rows.any { it.key == rowKey })
    }

    /** This layout with [rowKey]'s row moved to [toIndex] in its own section; unchanged when it is not here. */
    fun moved(rowKey: String, toIndex: Int): RosterLayout<T> {
        val row = rows.firstOrNull { it.key == rowKey } ?: return this
        val target = toIndex.coerceIn(0, row.sectionSize - 1)
        if (target == row.indexInSection) return this
        val next = sections.map { section ->
            if (section.key != row.sectionKey) return@map section
            val items = section.items.toMutableList().apply { add(target, removeAt(row.indexInSection)) }
            section.copy(items = items)
        }
        return RosterLayout(next, key, columns)
    }

    private fun buildLines(): List<RosterLine<T>> = buildList {
        sections.forEach { section ->
            if (section.title != null) add(RosterLine.Header(section))
            val rows = section.items.mapIndexed { index, item ->
                RosterLine.Row(item, rowKey(section.key, key(item)), section.key, index, section.items.size)
            }
            if (columns > 1) rows.chunked(columns).forEach { add(RosterLine.Cells(it)) } else addAll(rows)
            if (section.items.isEmpty()) section.emptyHint?.let { add(RosterLine.Hint(section.key, it)) }
        }
    }

    companion object {
        /** A row's key: unique across sections, since one item may sit in two (a pin and its group). */
        fun rowKey(sectionKey: String, itemKey: String): String = "$sectionKey:$itemKey"

        fun <T> of(sections: List<RosterSection<T>>, columns: Int = 1, key: (T) -> String): RosterLayout<T> =
            RosterLayout(sections, key, columns.coerceAtLeast(1))
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
