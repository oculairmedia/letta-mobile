package com.letta.mobile.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Columns2
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Rows2
import io.github.linreal.cascade.editor.core.Block
import io.github.linreal.cascade.editor.core.BlockContent
import io.github.linreal.cascade.editor.core.CustomBlockType
import io.github.linreal.cascade.editor.registry.BlockCallbacks
import io.github.linreal.cascade.editor.registry.BlockDescriptor
import io.github.linreal.cascade.editor.registry.BlockPreviewRenderer
import io.github.linreal.cascade.editor.registry.BlockPreviewScope
import io.github.linreal.cascade.editor.registry.BlockRegistry
import io.github.linreal.cascade.editor.registry.BlockRenderScope
import io.github.linreal.cascade.editor.registry.ScopedBlockRenderer

/**
 * A table block for notes, as a Cascade custom block: its cells live in the block's custom
 * content as `rows: [[cell, ...], ...]`, so the document JSON the session stores carries the
 * whole table and peers and the agent read it back. The type id is namespaced so a document
 * from another editor never collides with it.
 */
object TableBlockType : CustomBlockType {
    override val typeId: String = CanvasTableBlock.TYPE_ID
    override val displayName: String = "Table"
    override val supportsText: Boolean = false
    override val isConvertible: Boolean = false
    override val supportsIndentation: Boolean = false
    override val supportsSpans: Boolean = false
}

object CanvasTableBlock {
    const val TYPE_ID = "letta.table"
    const val ROWS = "rows"
    private const val DEFAULT_ROWS = 2
    private const val DEFAULT_COLUMNS = 2

    /** The cells of [block], rows of strings; an empty table for anything that is not one. */
    fun rowsOf(block: Block): List<List<String>> {
        val data = (block.content as? BlockContent.Custom)?.data ?: return emptyList()
        val rows = data[ROWS] as? List<*> ?: return emptyList()
        return rows.map { row -> (row as? List<*>)?.map { it?.toString().orEmpty() } ?: emptyList() }
    }

    fun content(rows: List<List<String>>): BlockContent = BlockContent.Custom(TYPE_ID, mapOf(ROWS to rows))

    fun emptyRows(rows: Int = DEFAULT_ROWS, columns: Int = DEFAULT_COLUMNS): List<List<String>> =
        List(rows) { List(columns) { "" } }

    /** How the editor offers the block (slash menu, formatting bar) and makes a fresh one. */
    val descriptor: BlockDescriptor = BlockDescriptor(
        typeId = TYPE_ID,
        displayName = "Table",
        description = "Rows and columns of cells",
        keywords = listOf("table", "grid", "cells"),
        icon = "table",
        factory = { id -> Block(id, TableBlockType, content(emptyRows())) },
    )

    /** The default registry plus the table, for every editor and preview on the board. */
    fun registry(): BlockRegistry = BlockRegistry.createDefault().apply {
        register(descriptor, TableBlockRenderer)
        registerPreviewRenderer(TYPE_ID, TablePreviewRenderer)
    }
}

@Composable
internal fun rememberCanvasBlockRegistry(): BlockRegistry = remember { CanvasTableBlock.registry() }

/** The editable table: a grid of text fields, plus row and column controls, writing the block on every change. */
internal object TableBlockRenderer : ScopedBlockRenderer<TableBlockType> {
    override val handlesSelectionVisual: Boolean get() = false
    override val supportsDragPreview: Boolean get() = true

    @Composable
    override fun Render(
        block: Block,
        selected: Boolean,
        dragging: Boolean,
        modifier: Modifier,
        callbacks: BlockCallbacks,
        scope: BlockRenderScope,
    ) {
        val rows = CanvasTableBlock.rowsOf(block)
        fun write(next: List<List<String>>) = scope.updateBlock(block.id) { it.withContent(CanvasTableBlock.content(next)) }
        Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            TableGrid(rows = rows, editable = !scope.readOnly && scope.canUpdateBlock) { r, c, text ->
                write(rows.mapIndexed { ri, row -> if (ri == r) row.mapIndexed { ci, cell -> if (ci == c) text else cell } else row })
            }
            if (!scope.readOnly && scope.canEditBlockStructure) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    SmallAction(Lucide.Rows2, "Add table row") { write(rows + listOf(List(rows.firstOrNull()?.size ?: 1) { "" })) }
                    SmallAction(Lucide.Columns2, "Add table column") { write(rows.map { it + "" }) }
                    if (rows.size > 1) SmallAction(Lucide.Minus, "Remove table row") { write(rows.dropLast(1)) }
                    if ((rows.firstOrNull()?.size ?: 0) > 1) SmallAction(Lucide.Minus, "Remove table column") { write(rows.map { it.dropLast(1) }) }
                }
            }
        }
    }
}

/** The read-only table for previews (a note whose editor is open elsewhere). */
internal object TablePreviewRenderer : BlockPreviewRenderer<TableBlockType> {
    @Composable
    override fun RenderPreview(block: Block, modifier: Modifier, scope: BlockPreviewScope) {
        TableGrid(rows = CanvasTableBlock.rowsOf(block), editable = false, modifier = modifier) { _, _, _ -> }
    }
}

@Composable
private fun TableGrid(
    rows: List<List<String>>,
    editable: Boolean,
    modifier: Modifier = Modifier,
    onCell: (row: Int, column: Int, text: String) -> Unit,
) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Column(modifier = modifier.border(1.dp, line, RoundedCornerShape(4.dp)).semantics { contentDescription = "Table" }) {
        rows.forEachIndexed { r, row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEachIndexed { c, cell ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .widthIn(min = CELL_MIN_WIDTH)
                            .border(0.5.dp, line)
                            .background(if (r == 0) MaterialTheme.colorScheme.surfaceContainer else androidx.compose.ui.graphics.Color.Transparent)
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    ) {
                        if (editable) {
                            // The field keeps its own text while typing and writes every change to the
                            // block; a change that arrives from the block (undo, a peer) resets it.
                            var text by remember(cell) { mutableStateOf(cell) }
                            BasicTextField(
                                value = text,
                                onValueChange = { text = it; onCell(r, c, it) },
                                textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Table cell ${r + 1},${c + 1}" },
                            )
                        } else {
                            Text(cell, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(24.dp).semantics { contentDescription = label }) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
    }
}

private val CELL_MIN_WIDTH = 48.dp
