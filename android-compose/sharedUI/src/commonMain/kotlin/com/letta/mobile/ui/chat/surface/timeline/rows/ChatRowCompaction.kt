package com.letta.mobile.ui.chat.surface.timeline.rows

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.rows_compaction_empty
import com.letta.mobile.sharedui.resources.rows_compaction_title
import com.letta.mobile.sharedui.resources.rows_expand
import com.letta.mobile.sharedui.resources.rows_collapse
import com.letta.mobile.sharedui.resources.rows_state_collapsed
import com.letta.mobile.sharedui.resources.rows_state_expanded
import com.letta.mobile.ui.components.DisclosureChevron
import com.letta.mobile.ui.markdown.SharedMarkdownText
import com.letta.mobile.ui.theme.ChatRowType
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LettaMotionTokens
import com.letta.mobile.ui.theme.LocalReducedMotion
import org.jetbrains.compose.resources.stringResource

/** letta-mobile-kr39h: test tags of the compaction divider. */
object ChatCompactionTestTags {
    const val ROW = "chat-compaction-row"
    const val TOGGLE = "chat-compaction-toggle"
    const val SUMMARY = "chat-compaction-summary"
}

/**
 * letta-mobile-kr39h: where the conversation was compacted. A quiet divider titled "Conversation
 * compacted" whose chevron opens the summary the server kept in place of the older messages. It
 * is never a bubble: nobody said it. The open state is the row's own (a reading aid, not session
 * state), saved across recomposition and configuration change.
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
        CompactionHeader(expanded) { expanded = !expanded }
        if (expanded) CompactionSummary(summary)
    }
}

@Composable
private fun CompactionHeader(expanded: Boolean, onToggle: () -> Unit) {
    val click = rememberQuietClick()
    val stateText = stringResource(if (expanded) Res.string.rows_state_expanded else Res.string.rows_state_collapsed)
    val clickLabel = stringResource(if (expanded) Res.string.rows_collapse else Res.string.rows_expand)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(ChatCompactionTestTags.TOGGLE)
            .semantics(mergeDescendants = true) { stateDescription = stateText }
            .quietClickable(click, onClickLabel = clickLabel, onClick = onToggle)
            .heightIn(min = LettaDimens.Orb.railSlotHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        Text(text = stringResource(Res.string.rows_compaction_title), style = ChatRowType.sectionTitle, color = color)
        DisclosureChevron(expanded = expanded)
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun CompactionSummary(summary: String) {
    val text = summary.ifBlank { stringResource(Res.string.rows_compaction_empty) }
    SelectionContainer(Modifier.testTag(ChatCompactionTestTags.SUMMARY)) {
        SharedMarkdownText(text = text, textColor = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
