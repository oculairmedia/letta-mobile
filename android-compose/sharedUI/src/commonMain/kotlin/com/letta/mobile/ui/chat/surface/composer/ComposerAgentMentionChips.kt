package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.composer.AgentMentionRef
import com.letta.mobile.data.composer.AgentMentions
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.composer_agent_mentions
import com.letta.mobile.sharedui.resources.composer_remove_agent_mention
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import org.jetbrains.compose.resources.stringResource

/**
 * letta-mobile-zxk2n.6 (G31): the agents the draft mentions, one input chip each, above the
 * prompt. The reference itself stays in the text (that is what the agent receives, see
 * [AgentMentions]); removing a chip removes the whole reference from the draft. The row grows and
 * shrinks in place rather than pushing the prompt in one jump, and snaps under reduced motion.
 */
@Composable
internal fun ComposerAgentMentionChips(text: String, onTextChange: (String) -> Unit) {
    val mentions = remember(text) { AgentMentions.parse(text) }
    // Kept through the exit animation, so the row does not empty before it collapses.
    var shown by remember { mutableStateOf(mentions) }
    if (mentions.isNotEmpty()) shown = mentions
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(
        visible = mentions.isNotEmpty(),
        enter = if (reduced) EnterTransition.None else expandVertically() + fadeIn(),
        exit = if (reduced) ExitTransition.None else shrinkVertically() + fadeOut(),
    ) {
        val rowLabel = stringResource(Res.string.composer_agent_mentions)
        Row(
            modifier = Modifier
                .widthIn(max = ChatColumnMaxWidth)
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .semantics { contentDescription = rowLabel }
                .testTag(ComposerAgentMentionTags.ROW),
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            shown.forEach { mention ->
                // Only a reference still in the current draft is removed (not one mid-exit).
                AgentMentionChip(mention, onRemove = { if (mention in mentions) onTextChange(AgentMentions.remove(text, mention)) })
            }
        }
    }
}

@Composable
private fun AgentMentionChip(mention: AgentMentionRef, onRemove: () -> Unit) {
    val removeLabel = stringResource(Res.string.composer_remove_agent_mention, mention.name)
    InputChip(
        selected = false,
        onClick = onRemove,
        label = { Text(mention.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        avatar = {
            Icon(Icons.Outlined.SmartToy, contentDescription = null, modifier = Modifier.size(InputChipDefaults.AvatarSize))
        },
        trailingIcon = {
            Icon(Icons.Outlined.Close, contentDescription = removeLabel, modifier = Modifier.size(InputChipDefaults.IconSize))
        },
        modifier = Modifier.testTag(ComposerAgentMentionTags.CHIP + mention.agentId),
    )
}
