package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_compaction_empty
import com.letta.mobile.sharedui.resources.rows_compaction_title
import com.letta.mobile.ui.components.DividerLine
import com.letta.mobile.ui.components.line.LineDisclosure
import com.letta.mobile.ui.components.line.LineRail
import com.letta.mobile.ui.markdown.SharedMarkdownText
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaMotionTokens
import com.letta.mobile.ui.theme.LocalReducedMotion
import org.jetbrains.compose.resources.stringResource

/** Test tags of the compaction divider. */
internal object ChatCompactionTestTags {
    const val ROW = "chat-compaction-row"
    const val TOGGLE = "chat-compaction-toggle"
    const val SUMMARY = "chat-compaction-summary"
}

/**
 * Where the conversation was compacted. A quiet divider titled "Conversation
 * compacted" whose chevron opens the summary the server kept in place of the older messages. It
 * is never a bubble: nobody said it. It sits between turns, so it is the shared [DividerLine] on the
 * header rail. The open state is the row's own (a reading aid, not session state), saved across
 * recomposition and configuration change.
 */
@Composable
fun CompactionDividerRow(summary: String, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val reducedMotion = LocalReducedMotion.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag(ChatCompactionTestTags.ROW)
            .then(if (reducedMotion) Modifier else Modifier.animateContentSize(tween(LettaMotionTokens.CONTENT_SIZE_MILLIS)))
            .padding(vertical = LettaDimens.Space.xs),
    ) {
        DividerLine(
            label = stringResource(Res.string.rows_compaction_title),
            tag = ChatCompactionTestTags.TOGGLE,
            rail = LineRail.Header,
            disclosure = LineDisclosure(expanded) { expanded = !expanded },
        )
        if (expanded) CompactionSummary(summary)
    }
}

@Composable
private fun CompactionSummary(summary: String) {
    val text = summary.ifBlank { stringResource(Res.string.rows_compaction_empty) }
    SelectionContainer(Modifier.testTag(ChatCompactionTestTags.SUMMARY)) {
        SharedMarkdownText(text = text, textColor = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
