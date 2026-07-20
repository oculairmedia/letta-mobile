package com.letta.mobile.ui.screens.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.letta.mobile.R
import com.letta.mobile.ui.components.LettaInputBar
import com.letta.mobile.ui.components.ShimmerBox
import com.letta.mobile.ui.theme.LettaSpacing

@Composable
internal fun HomeContent(
    state: DashboardUiState,
    callbacks: HomeContentCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().imePadding()) {
        state.error?.let { error ->
            androidx.compose.material3.Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = LettaSpacing.screenHorizontal)
                    .padding(bottom = LettaSpacing.cardGap),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        if (state.isSearchActive) {
            SearchResultsContent(
                agentResults = state.agentResults,
                messageResults = state.messageResults,
                toolResults = state.toolResults,
                blockResults = state.blockResults,
                isSearching = state.isSearching,
                searchQuery = state.searchQuery,
                onAgentClick = { agent -> callbacks.onNavigateToChat(agent.id.value, agent.name, null) },
                onMessageClick = { parsed ->
                    val agentId = parsed.agentId ?: return@SearchResultsContent
                    val convId = parsed.conversationId
                    val msgId = parsed.messageId
                    if (convId != null && msgId != null) {
                        callbacks.onNavigateToChatMessage(agentId, convId, msgId)
                    } else {
                        callbacks.onNavigateToChat(agentId, null, null)
                    }
                },
                onToolClick = { callbacks.onNavigateToTools() },
                onBlockClick = { callbacks.onNavigateToBlocks() },
                modifier = Modifier.weight(1f),
            )
        } else {
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LettaSpacing.cardGap),
            ) {
                if (state.isPinnedItemsLoading) {
                    HomePinnedItemsLoadingShimmer()
                } else if (state.pinnedItems.isNotEmpty()) {
                    ReorderablePinnedItemsGrid(
                        items = state.pinnedItems,
                        state = state,
                        onShortcutClick = callbacks.onShortcutClick,
                        onUnpinShortcut = callbacks.onUnpinShortcut,
                        onAgentClick = { callbacks.onNavigateToChat(it.id, it.name, null) },
                        onUnpinAgent = { callbacks.onUnpinAgent(it.id) },
                        onConfigureAgent = { callbacks.onNavigateToEditAgent(it.id) },
                        onReorder = callbacks.onReorderPinnedItems,
                        columns = 3,
                        modifier = Modifier.padding(horizontal = LettaSpacing.screenHorizontal),
                    )
                }
            }

            if (state.favoriteAgentId != null) {
                var homeChatText by remember { mutableStateOf("") }
                LettaInputBar(
                    text = homeChatText,
                    onTextChange = { homeChatText = it },
                    onSend = { message ->
                        callbacks.onNavigateToChat(state.favoriteAgentId, state.favoriteAgentName, message)
                        homeChatText = ""
                    },
                    placeholder = stringResource(R.string.screen_home_chat_placeholder),
                    sendContentDescription = stringResource(R.string.action_send_message),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun HomePinnedItemsLoadingShimmer() {
    Column(
        modifier = Modifier.padding(horizontal = LettaSpacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(LettaSpacing.cardGap),
    ) {
        for (row in 0..2) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(LettaSpacing.cardGap),
            ) {
                for (col in 0..2) {
                    ShimmerBox(
                        modifier = Modifier.weight(1f),
                        height = 100.dp,
                    )
                }
            }
        }
    }
}
