package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.a2ui.A2uiSurfaceState
import com.letta.mobile.data.chat.projection.IncrementalChatRenderItemsCache
import com.letta.mobile.data.chat.projection.toChatDisplayMode
import com.letta.mobile.feature.chat.R
import com.letta.mobile.ui.a2ui.A2uiSurfaceRenderer
import com.letta.mobile.ui.chat.render.A2uiDebugFrameUi
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.render.GoalStatusUi
import com.letta.mobile.ui.components.StarterPrompts
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.ChatBackground
import com.letta.mobile.ui.theme.LettaSpacing
import kotlinx.collections.immutable.ImmutableMap

internal fun shouldShowStarterPromptsForNoConversation(state: ChatUiState): Boolean =
    state.messages.isEmpty() && !state.isStreaming && state.a2uiSurfaces.isEmpty()

@Composable
internal fun NoConversationChatContent(
    state: ChatUiState,
    callbacks: ChatContentCallbacks,
    appearance: ChatContentAppearance,
    modifier: Modifier = Modifier,
) {
    if (shouldShowStarterPromptsForNoConversation(state)) {
        StarterPrompts(
            onPromptClick = callbacks.onSendMessage,
            modifier = modifier.padding(
                top = appearance.topPadding,
                bottom = appearance.bottomPadding,
            ),
        )
    } else {
        ChatContent(
            state = state,
            callbacks = callbacks,
            appearance = appearance,
            modifier = modifier,
        )
    }
}

@Composable
internal fun ChatContent(
    state: ChatUiState,
    callbacks: ChatContentCallbacks,
    appearance: ChatContentAppearance,
    modifier: Modifier = Modifier,
) {
    val renderItems = rememberChatRenderItems(state, appearance.chatMode)
    var a2uiStackHeightDp by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val showStarterPrompts = state.messages.isEmpty() && !state.isStreaming && state.a2uiSurfaces.isEmpty()

    Box(modifier = modifier.fillMaxSize()) {
        if (showStarterPrompts) {
            StarterPrompts(
                onPromptClick = callbacks.onSendMessage,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = appearance.topPadding, bottom = appearance.bottomPadding),
            )
        } else {
            ChatContentMessageArea(
                state = state,
                renderItems = renderItems,
                callbacks = callbacks,
                appearance = appearance,
                a2uiStackHeightDp = a2uiStackHeightDp,
            )
            if (state.a2uiSurfaces.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = appearance.bottomPadding)
                        .onSizeChanged { size ->
                            a2uiStackHeightDp = with(density) { size.height.toDp() }
                        },
                ) {
                    A2uiSurfaceStack(
                        params = A2uiSurfaceStackParams(
                            surfaces = state.a2uiSurfaces,
                            resolvedActionCounters = state.a2uiResolvedActionCounters,
                            onAction = callbacks.onA2uiAction,
                            onDismissSurface = callbacks.onDismissA2uiSurface,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = LettaSpacing.LG, vertical = LettaSpacing.SM),
                    )
                }
            }
        }
    }
}

@Composable
private fun rememberChatRenderItems(
    state: ChatUiState,
    chatMode: String,
): List<com.letta.mobile.data.chat.projection.ChatRenderItem> {
    val renderItemsCache = remember { IncrementalChatRenderItemsCache() }
    val chatDisplayMode = chatMode.toChatDisplayMode()
    return remember(state.messages, chatMode, state.messageListChange) {
        renderItemsCache.renderItems(
            messages = state.messages,
            mode = chatDisplayMode,
            change = state.messageListChange,
            activeAgentId = state.agentId,
        ).also { built ->
            com.letta.mobile.ui.chat.render.RenderDiagnostics.onRenderItemsBuilt(
                conversationId = (state.conversationState as? ConversationState.Ready)?.conversationId ?: "<active>",
                path = state.messageListChange.toString(),
                items = built,
            )
            com.letta.mobile.ui.chat.render.RenderDiagnostics.onRenderScopeProjection(
                activeAgentId = state.agentId,
                conversationId = (state.conversationState as? ConversationState.Ready)?.conversationId ?: "<active>",
                items = built,
            )
        }
    }
}

@Composable
private fun ChatContentMessageArea(
    state: ChatUiState,
    renderItems: List<com.letta.mobile.data.chat.projection.ChatRenderItem>,
    callbacks: ChatContentCallbacks,
    appearance: ChatContentAppearance,
    a2uiStackHeightDp: Dp,
) {
    val hasMessagesOrStreaming = state.messages.isNotEmpty() || state.isStreaming
    if (!hasMessagesOrStreaming) return

    val listBottomPadding = appearance.bottomPadding +
        if (state.a2uiSurfaces.isNotEmpty()) a2uiStackHeightDp else 0.dp
    ChatMessageList(
        state = state,
        renderItems = renderItems,
        chatMode = appearance.chatMode,
        scrollToMessageId = appearance.scrollToMessageId,
        activeFontScale = appearance.activeFontScale,
        onActiveFontScaleChange = callbacks.onActiveFontScaleChange,
        onFontScaleChange = callbacks.onFontScaleChange,
        onLoadOlderMessages = callbacks.onLoadOlderMessages,
        onSendMessage = callbacks.onSendMessage,
        onRerunMessage = callbacks.onRerunMessage,
        onSubmitApproval = callbacks.onSubmitApproval,
        onToggleRunCollapsed = callbacks.onToggleRunCollapsed,
        onToggleReasoningExpanded = callbacks.onToggleReasoningExpanded,
        onAttachmentImageTap = callbacks.onAttachmentImageTap,
        modifier = Modifier.fillMaxSize(),
        chatBackground = appearance.chatBackground,
        topPadding = appearance.topPadding,
        bottomPadding = listBottomPadding,
    )
}

internal data class A2uiSurfaceStackParams(
    val surfaces: ImmutableMap<String, A2uiSurfaceState>,
    val resolvedActionCounters: Map<String, Int>,
    val onAction: (A2uiAction) -> Unit,
    val onDismissSurface: (String) -> Unit,
)

@Composable
internal fun A2uiSurfaceStack(
    params: A2uiSurfaceStackParams,
    modifier: Modifier = Modifier,
) {
    if (params.surfaces.isEmpty()) return
    val orderedSurfaces = remember(params.surfaces) {
        params.surfaces.values.sortedBy(A2uiSurfaceState::surfaceId)
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(LettaSpacing.SM),
    ) {
        orderedSurfaces.forEach { surface ->
            key(surface.surfaceId) {
                DismissibleA2uiSurface(
                    surfaceId = surface.surfaceId,
                    onDismissSurface = params.onDismissSurface,
                ) {
                    A2uiSurfaceRenderer(
                        surface = surface,
                        modifier = Modifier.fillMaxWidth(),
                        onAction = params.onAction,
                        actionResolutionToken = params.resolvedActionCounters[surface.surfaceId] ?: 0,
                    )
                }
            }
        }
    }
}

@Composable
internal fun DismissibleA2uiSurface(
    surfaceId: String,
    onDismissSurface: (String) -> Unit,
    content: @Composable () -> Unit,
) {
    var menuExpanded by remember(surfaceId) { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("Delete A2UI surface") {
                        onDismissSurface(surfaceId)
                        true
                    }
                )
            }
            .longPressPassthrough { menuExpanded = true },
    ) {
        content()
        androidx.compose.material3.IconButton(
            onClick = { onDismissSurface(surfaceId) },
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Icon(
                imageVector = LettaIcons.Close,
                contentDescription = "Close A2UI surface",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                leadingIcon = {
                    Icon(
                        imageVector = LettaIcons.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                },
                onClick = {
                    try {
                        onDismissSurface(surfaceId)
                    } finally {
                        menuExpanded = false
                    }
                },
            )
        }
    }
}

@Composable
internal fun ChatScreenErrorContent(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = LettaIcons.Error,
            contentDescription = "Error",
            modifier = Modifier.size(LettaSpacing.XXXL),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(LettaSpacing.LG))
        Text(text = message, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(LettaSpacing.LG))
        Button(onClick = onRetry) {
            Text(stringResource(R.string.action_retry))
        }
    }
}

@Composable
internal fun GoalStatusCard(
    goal: GoalStatusUi?,
    loading: Boolean,
    callbacks: GoalStatusCallbacks,
) {
    if (goal == null && !loading) return
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.94f),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GoalStatusCardHeader(goal = goal, loading = loading, onRefresh = callbacks.onRefresh)
            if (goal != null) {
                GoalStatusCardDetails(goal = goal)
                GoalStatusCardActions(goal = goal, callbacks = callbacks)
            } else {
                GoalStatusCardLoadingBody()
            }
        }
    }
}

@Composable
private fun GoalStatusCardHeader(
    goal: GoalStatusUi?,
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (loading) "Goal" else "Goal • ${goal?.status.orEmpty()}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onRefresh) { Text("Refresh") }
    }
}

@Composable
private fun GoalStatusCardDetails(goal: GoalStatusUi) {
    Text(
        text = goal.objective,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
    )
    val budget = goal.tokenBudget?.let { " / $it" }.orEmpty()
    Text(
        text = "Tokens ${goal.tokensUsed}$budget • active ${goal.activeTimeSeconds}s",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun GoalStatusCardLoadingBody() {
    Text(
        text = "Loading goal status…",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun GoalStatusCardActions(
    goal: GoalStatusUi,
    callbacks: GoalStatusCallbacks,
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (goal.status == "complete") {
            TextButton(onClick = callbacks.onClear) { Text("Clear") }
            return
        }
        Button(onClick = callbacks.onContinue, enabled = goal.status == "active") { Text("Continue") }
        if (goal.status == "paused") {
            TextButton(onClick = callbacks.onResume) { Text("Resume") }
        } else {
            TextButton(onClick = callbacks.onPause, enabled = goal.status == "active") { Text("Pause") }
        }
        TextButton(onClick = callbacks.onComplete) { Text("Done") }
        TextButton(onClick = callbacks.onClear) { Text("Clear") }
    }
}

@Composable
internal fun A2uiDebugOverlay(
    frames: List<A2uiDebugFrameUi>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shape = MaterialTheme.shapes.small,
            )
            .padding(LettaSpacing.SM),
    ) {
        Text(
            text = "A2UI frames",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = LettaSpacing.XS))
        LazyColumn(modifier = Modifier.height(LettaSpacing.XXXL.times(2))) {
            items(frames.takeLast(8).asReversed(), key = { it.id }) { frame ->
                Text(
                    text = buildString {
                        append(frame.messageType)
                        frame.surfaceId?.let { append(" / ").append(it) }
                        frame.conversationId?.takeLast(6)?.let { append(" / conv:").append(it) }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
