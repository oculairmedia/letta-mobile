package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.timeline_error_title
import com.letta.mobile.sharedui.resources.timeline_no_conversation_title
import com.letta.mobile.sharedui.resources.timeline_retry
import com.letta.mobile.sharedui.resources.timeline_starter_capabilities
import com.letta.mobile.sharedui.resources.timeline_starter_get_started
import com.letta.mobile.sharedui.resources.timeline_starter_help
import com.letta.mobile.sharedui.resources.timeline_starter_limitations
import com.letta.mobile.sharedui.resources.timeline_welcome_default_agent
import com.letta.mobile.sharedui.resources.timeline_welcome_greeting
import com.letta.mobile.sharedui.resources.timeline_welcome_subtitle
import com.letta.mobile.ui.chat.ChatColumnMaxWidth
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.ChatTimelineDimens
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Skeleton row pattern: user, agent, user, agent, agent, user, agent (Android MessageSkeletonList). */
private val SkeletonPattern = listOf(true, false, true, false, false, true, false)

/** letta-mobile-bglj6.1: first-load placeholder (Android MessageSkeletonList). */
@Composable
internal fun TimelineLoadingSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(vertical = LettaDimens.Space.sm).testTag(ChatTimelineTags.SKELETON),
    ) {
        SkeletonPattern.forEachIndexed { index, isUser -> SkeletonBubble(isUser = isUser, index = index) }
    }
}

@Composable
private fun SkeletonBubble(isUser: Boolean, index: Int) {
    val transition = rememberInfiniteTransition(label = "timelineSkeleton")
    val pulse by transition.animateFloat(
        initialValue = ChatTimelineDimens.Alpha.skeletonPulseLow,
        targetValue = ChatTimelineDimens.Alpha.skeletonPulseHigh,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = ChatTimelineDimens.skeletonPulseMillis,
                delayMillis = index * ChatTimelineDimens.skeletonStaggerMillis,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "timelineSkeletonPulse$index",
    )
    val maxWidth = if (isUser) ChatTimelineDimens.skeletonUserBubbleMaxWidth else ChatTimelineDimens.skeletonAgentBubbleMaxWidth
    val lines = if (isUser) listOf(1f, 0.8f) else listOf(1f, 0.8f, 0.6f)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.xs),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = maxWidth)
                .clip(RoundedCornerShape(LettaDimens.Radius.md))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .alpha(pulse)
                .padding(LettaDimens.Space.md),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            lines.forEach { fraction -> SkeletonLine(fraction) }
        }
    }
}

@Composable
private fun SkeletonLine(fraction: Float) {
    Box(
        modifier = Modifier
            .fillMaxWidth(fraction)
            .height(LettaDimens.Space.md)
            .clip(RoundedCornerShape(LettaDimens.Radius.sm))
            .background(MaterialTheme.colorScheme.outline.copy(alpha = ChatTimelineDimens.Alpha.skeletonLine)),
    )
}

/**
 * letta-mobile-bglj6.1: a failed conversation or connection, with a retry (Android
 * ChatScreenErrorContent; desktop ChatStatePanel's failure headline).
 */
@Composable
internal fun TimelineStatusPanel(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().testTag(ChatTimelineTags.STATUS), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = ChatColumnMaxWidth).padding(LettaDimens.Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg),
        ) {
            Icon(
                imageVector = LettaIcons.Error,
                contentDescription = null,
                modifier = Modifier.size(ChatTimelineDimens.statusIcon),
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                text = stringResource(Res.string.timeline_error_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = ChatTimelineDimens.proseMaxWidth),
            )
            Button(onClick = onRetry) { Text(stringResource(Res.string.timeline_retry)) }
        }
    }
}

private class StarterPrompt(val text: StringResource, val icon: ImageVector)

private val StarterPrompts = listOf(
    StarterPrompt(Res.string.timeline_starter_help, LettaIcons.AutoAwesome),
    StarterPrompt(Res.string.timeline_starter_capabilities, LettaIcons.Psychology),
    StarterPrompt(Res.string.timeline_starter_get_started, LettaIcons.Help),
    StarterPrompt(Res.string.timeline_starter_limitations, LettaIcons.Lightbulb),
)

/**
 * letta-mobile-bglj6.1: the empty conversation (desktop NewConversationWelcome greeting, Android
 * StarterPrompts). A prompt is SENT, not drafted: Android's behaviour, and the one-tap first move.
 */
@Composable
internal fun TimelineWelcome(
    agentName: String,
    hasConversation: Boolean,
    onStarterPrompt: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** The conversation's agent: its mascot greets at hero size above the headline. */
    agentId: String? = null,
    /** The mascot's pencil badge: open the agent's editor. */
    onEditAgent: (() -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize().testTag(ChatTimelineTags.WELCOME), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = ChatColumnMaxWidth).padding(LettaDimens.Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        ) {
            if (hasConversation) TimelineWelcomeHero(agentId, onEditAgent)
            WelcomeHeadline(agentName, hasConversation)
            Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
                StarterPrompts.forEach { prompt ->
                    StarterPromptCard(text = stringResource(prompt.text), icon = prompt.icon, onClick = onStarterPrompt)
                }
            }
        }
    }
}

@Composable
private fun WelcomeHeadline(agentName: String, hasConversation: Boolean) {
    val title = if (hasConversation) {
        val name = agentName.ifBlank { stringResource(Res.string.timeline_welcome_default_agent) }
        stringResource(Res.string.timeline_welcome_greeting, name)
    } else {
        stringResource(Res.string.timeline_no_conversation_title)
    }
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
    )
    Text(
        text = stringResource(Res.string.timeline_welcome_subtitle),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = ChatTimelineDimens.proseMaxWidth).padding(bottom = LettaDimens.Space.sm),
    )
}

@Composable
private fun StarterPromptCard(text: String, icon: ImageVector, onClick: (String) -> Unit) {
    Card(onClick = { onClick(text) }, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}
