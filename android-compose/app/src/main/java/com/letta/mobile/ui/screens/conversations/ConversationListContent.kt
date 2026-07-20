package com.letta.mobile.ui.screens.conversations

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.letta.mobile.R
import com.letta.mobile.ui.components.DateSeparator
import com.letta.mobile.ui.components.EmptyState
import com.letta.mobile.ui.haptics.HapticEffects
import com.letta.mobile.ui.motion.StaggeredListItem
import com.letta.mobile.ui.screens.agentlist.LocalLettaCodeCreateReadiness
import com.letta.mobile.ui.theme.sectionTitle
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal data class ConversationListContentState(
    val conversations: List<ConversationDisplay>,
    val isRefreshing: Boolean,
    val isSearchActive: Boolean,
    val showFirstRunOnboarding: Boolean,
    val localReadiness: LocalLettaCodeCreateReadiness,
    val onCreateFirstAgent: () -> Unit,
    val onOpenLocalSettings: () -> Unit,
)

internal fun ConversationDisplay.routeAgentName(): String? =
    agentName.takeIf { it.isNotBlank() && it != conversation.agentId.value.take(8) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationListContent(
    state: ConversationListContentState,
    actions: ConversationListActions,
    modifier: Modifier = Modifier,
) {
    if (state.conversations.isEmpty()) {
        ConversationListEmptyContent(
            state = state,
            modifier = modifier,
        )
        return
    }

    ConversationListRefreshableContent(
        state = state,
        actions = actions,
        modifier = modifier,
    )
}

@Composable
private fun ConversationListEmptyContent(
    state: ConversationListContentState,
    modifier: Modifier = Modifier,
) {
    if (state.showFirstRunOnboarding) {
        FirstRunWelcomeCard(
            localReadiness = state.localReadiness,
            onCreateFirstAgent = state.onCreateFirstAgent,
            onOpenLocalSettings = state.onOpenLocalSettings,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    EmptyState(
        icon = LettaIcons.ChatOutline,
        message = stringResource(
            if (state.isSearchActive) R.string.screen_conversations_search_empty
            else R.string.screen_conversations_empty,
        ),
        modifier = modifier.fillMaxSize(),
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ConversationListRefreshableContent(
    state: ConversationListContentState,
    actions: ConversationListActions,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = {
            HapticEffects.confirm(haptic, view)
            actions.onRefresh()
        },
        modifier = modifier.fillMaxSize(),
    ) {
        ConversationListSections(
            conversations = state.conversations,
            actions = actions,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationListSections(
    conversations: List<ConversationDisplay>,
    actions: ConversationListActions,
) {
    val sections = remember(conversations) {
        buildConversationSections(conversations)
    }
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        var runningIndex = 0
        sections.forEach { section ->
            val sectionBaseIndex = runningIndex
            item(key = section.key) {
                ConversationSectionHeader(section = section)
            }
            itemsIndexed(
                items = section.items,
                key = { index, display -> "${section.key}:${display.conversation.id}:$index" },
            ) { index, display ->
                StaggeredListItem(index = sectionBaseIndex + index) {
                    ConversationCard(
                        display = display,
                        callbacks = ConversationCardCallbacks(
                            onClick = { actions.onConversationClick(display) },
                            onOpenAdmin = { actions.onOpenAdmin(display) },
                            onDelete = { actions.onDeleteConversation(display) },
                            onRename = { newName -> actions.onRenameConversation(display, newName) },
                            onTogglePinned = { actions.onTogglePinned(display) },
                            onFork = { actions.onForkConversation(display) },
                        ),
                    )
                }
            }
            runningIndex += section.items.size
        }
    }
}

@Composable
private fun ConversationSectionHeader(section: ConversationSection) {
    when {
        section.isPinned -> ConversationPinnedHeader()
        section.date != null -> DateSeparator(date = section.date)
    }
}

@Composable
private fun ConversationPinnedHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = LettaIcons.Star,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(LettaIconSizing.Inline),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "Pinned",
            style = MaterialTheme.typography.sectionTitle,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

private data class ConversationSection(
    val key: String,
    val date: LocalDate? = null,
    val isPinned: Boolean = false,
    val items: List<ConversationDisplay>,
)

private fun buildConversationSections(conversations: List<ConversationDisplay>): List<ConversationSection> {
    if (conversations.isEmpty()) return emptyList()

    val deduped = conversations.distinctBy { it.conversation.id }
    val pinned = deduped.filter { it.isPinned }
    val regular = deduped.filterNot { it.isPinned }

    val sections = mutableListOf<ConversationSection>()
    if (pinned.isNotEmpty()) {
        sections += ConversationSection(
            key = "pinned",
            isPinned = true,
            items = pinned,
        )
    }

    regular
        .groupBy { conversationLocalDate(it.conversation) }
        .forEach { (date, items) ->
            sections += ConversationSection(
                key = "date_$date",
                date = date,
                items = items,
            )
        }

    return sections
}

private fun conversationLocalDate(conversation: com.letta.mobile.data.model.Conversation): LocalDate {
    val timestamp = conversation.lastMessageAt ?: conversation.createdAt ?: Instant.EPOCH.toString()
    return runCatching {
        Instant.parse(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
    }.getOrDefault(LocalDate.now())
}
