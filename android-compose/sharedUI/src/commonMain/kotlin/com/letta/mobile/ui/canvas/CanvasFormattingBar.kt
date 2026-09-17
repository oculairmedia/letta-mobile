package com.letta.mobile.ui.canvas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import com.composables.icons.lucide.Italic
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Strikethrough
import com.composables.icons.lucide.Underline
import io.github.linreal.cascade.editor.core.SpanStyle
import io.github.linreal.cascade.editor.richtext.FormattingActions
import io.github.linreal.cascade.editor.richtext.FormattingState
import io.github.linreal.cascade.editor.richtext.StyleStatus

/**
 * The block editor's formatting controls, hoisted out of the note card to a bar at the foot of
 * the board. Cascade lets an editor hand its formatting state and actions to a custom toolbar
 * slot; [CanvasBlockEditor] passes them up and the workspace renders this for the active note,
 * so the controls are usable however small the note is.
 */
@Composable
fun CanvasFormattingBar(
    toolbar: NoteToolbar,
    modifier: Modifier = Modifier,
) {
    val formatting by toolbar.state
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f),
        tonalElevation = 2.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FormattingButtons.forEach { (style, icon, label) ->
                val active = formatting.styleStatusOf(style) != StyleStatus.Absent
                IconButton(
                    onClick = { toolbar.actions.toggleStyle(style) },
                    enabled = formatting.canFormat,
                    modifier = Modifier.size(BUTTON).semantics { contentDescription = label },
                    colors = if (active) IconButtonDefaults.filledTonalIconButtonColors() else IconButtonDefaults.iconButtonColors(),
                ) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** What an editor hands up for a hoisted toolbar: its live formatting state and the actions on it. */
class NoteToolbar(
    val state: State<FormattingState>,
    val actions: FormattingActions,
)

private data class FormattingButton(val style: SpanStyle, val icon: ImageVector, val label: String)

private val FormattingButtons = listOf(
    FormattingButton(SpanStyle.Bold, Lucide.Bold, "Bold"),
    FormattingButton(SpanStyle.Italic, Lucide.Italic, "Italic"),
    FormattingButton(SpanStyle.Underline, Lucide.Underline, "Underline"),
    FormattingButton(SpanStyle.StrikeThrough, Lucide.Strikethrough, "Strikethrough"),
    FormattingButton(SpanStyle.InlineCode, Lucide.Code, "Inline code"),
)

private val BUTTON = 36.dp
