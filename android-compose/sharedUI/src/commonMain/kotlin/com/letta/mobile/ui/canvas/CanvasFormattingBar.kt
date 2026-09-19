package com.letta.mobile.ui.canvas

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.Bold
import com.composables.icons.lucide.Code
import com.composables.icons.lucide.Heading1
import com.composables.icons.lucide.Heading2
import com.composables.icons.lucide.Heading3
import com.composables.icons.lucide.Italic
import com.composables.icons.lucide.List
import com.composables.icons.lucide.ListOrdered
import com.composables.icons.lucide.ListTodo
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Minus
import com.composables.icons.lucide.Table
import com.composables.icons.lucide.Pilcrow
import com.composables.icons.lucide.SquareCode
import com.composables.icons.lucide.Strikethrough
import com.composables.icons.lucide.TextQuote
import com.composables.icons.lucide.Underline
import io.github.linreal.cascade.editor.action.ConvertBlockType
import io.github.linreal.cascade.editor.action.InsertBlockAfter
import io.github.linreal.cascade.editor.core.Block
import io.github.linreal.cascade.editor.core.BlockType
import io.github.linreal.cascade.editor.core.SpanStyle
import io.github.linreal.cascade.editor.richtext.FormattingActions
import io.github.linreal.cascade.editor.richtext.FormattingState
import io.github.linreal.cascade.editor.richtext.StyleStatus
import io.github.linreal.cascade.editor.state.BlockSpanStates
import io.github.linreal.cascade.editor.state.BlockTextStates
import io.github.linreal.cascade.editor.state.EditorStateHolder
import com.letta.mobile.ui.theme.LettaDimens

/**
 * The block editor's controls, hoisted out of the note card to a bar at the foot of the board:
 * a row of block types (paragraph, headings, to-do, lists, quote, code, divider) that convert
 * the block the caret is in, and a row of inline styles. Cascade lets an editor hand its
 * formatting state and actions to a custom toolbar slot; [CanvasBlockEditor] passes them up with
 * its state holder and the workspace renders this for the active note, so the full set is usable
 * however small the note is.
 */
@Composable
fun CanvasFormattingBar(
    toolbar: NoteToolbar,
    modifier: Modifier = Modifier,
) {
    val formatting by toolbar.state
    val focusedType = toolbar.holder.state.focusedBlock?.type
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(LettaDimens.Radius.lg),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
        tonalElevation = LettaDimens.Space.hair,
        shadowElevation = LettaDimens.Space.sm,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BlockButtons.forEach { button ->
                    val active = focusedType != null && button.matches(focusedType)
                    BarButton(button.icon, button.label, active) { toolbar.apply(button) }
                }
            }
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FormattingButtons.forEach { (style, icon, label) ->
                    val active = formatting.styleStatusOf(style) != StyleStatus.Absent
                    BarButton(icon, label, active, enabled = formatting.canFormat) { toolbar.actions.toggleStyle(style) }
                }
            }
        }
    }
}

@Composable
private fun BarButton(icon: ImageVector, label: String, active: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(BUTTON).semantics { contentDescription = label },
        colors = if (active) IconButtonDefaults.filledTonalIconButtonColors() else IconButtonDefaults.iconButtonColors(),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.icon))
    }
}

@Suppress("unused")
@Composable
private fun BarDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = LettaDimens.Space.hair)
            .width(1.dp)
            .height(LettaDimens.Space.xl)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)),
    )
}

/**
 * What an editor hands up for a hoisted toolbar: its live formatting state and actions, and the
 * state it dispatches structural changes (block type, inserts) through.
 */
class NoteToolbar(
    val state: State<FormattingState>,
    val actions: FormattingActions,
    val holder: EditorStateHolder,
    val textStates: BlockTextStates,
    val spanStates: BlockSpanStates,
    private val onStructuralChange: () -> Unit = {},
) {
    /** Converts the block the caret is in, or inserts after it for block kinds that hold no text. */
    fun apply(button: BlockButton) {
        val focused = holder.state.focusedBlock ?: holder.state.blocks.lastOrNull() ?: return
        val action = when (val kind = button.kind) {
            is BlockKind.Convert -> ConvertBlockType(focused.id, kind.type)
            is BlockKind.Insert -> InsertBlockAfter(kind.block(), focused.id)
        }
        holder.dispatchStructuralAction(action, textStates, spanStates)
        onStructuralChange()
    }
}

/** How a bar button changes the document: convert the focused block, or insert a block after it. */
sealed interface BlockKind {
    data class Convert(val type: BlockType) : BlockKind
    data class Insert(val block: () -> Block) : BlockKind
}

data class BlockButton(val kind: BlockKind, val icon: ImageVector, val label: String) {
    fun matches(type: BlockType): Boolean = (kind as? BlockKind.Convert)?.type?.typeId == type.typeId
}

private data class FormattingButton(val style: SpanStyle, val icon: ImageVector, val label: String)

/** Every block kind the editor ships, in the order a slash menu lists them. */
val BlockButtons: List<BlockButton> = listOf(
    BlockButton(BlockKind.Convert(BlockType.Paragraph), Lucide.Pilcrow, "Paragraph"),
    BlockButton(BlockKind.Convert(BlockType.Heading(1)), Lucide.Heading1, "Heading 1"),
    BlockButton(BlockKind.Convert(BlockType.Heading(2)), Lucide.Heading2, "Heading 2"),
    BlockButton(BlockKind.Convert(BlockType.Heading(3)), Lucide.Heading3, "Heading 3"),
    BlockButton(BlockKind.Convert(BlockType.Todo(false)), Lucide.ListTodo, "To-do"),
    BlockButton(BlockKind.Convert(BlockType.BulletList), Lucide.List, "Bullet list"),
    BlockButton(BlockKind.Convert(BlockType.NumberedList()), Lucide.ListOrdered, "Numbered list"),
    BlockButton(BlockKind.Convert(BlockType.Quote), Lucide.TextQuote, "Quote"),
    BlockButton(BlockKind.Convert(BlockType.Code), Lucide.SquareCode, "Code block"),
    BlockButton(BlockKind.Insert { Block.divider() }, Lucide.Minus, "Divider"),
    BlockButton(BlockKind.Insert { CanvasTableBlock.descriptor.createBlock() }, Lucide.Table, "Table"),
)

private val FormattingButtons = listOf(
    FormattingButton(SpanStyle.Bold, Lucide.Bold, "Bold"),
    FormattingButton(SpanStyle.Italic, Lucide.Italic, "Italic"),
    FormattingButton(SpanStyle.Underline, Lucide.Underline, "Underline"),
    FormattingButton(SpanStyle.StrikeThrough, Lucide.Strikethrough, "Strikethrough"),
    FormattingButton(SpanStyle.InlineCode, Lucide.Code, "Inline code"),
)

private val BUTTON = LettaDimens.Space.xxl
