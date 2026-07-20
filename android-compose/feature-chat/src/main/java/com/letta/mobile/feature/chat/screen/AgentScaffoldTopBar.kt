package com.letta.mobile.feature.chat.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.feature.chat.R
import com.letta.mobile.ui.components.LettaSearchBar
import com.letta.mobile.ui.haptics.HapticEffects
import com.letta.mobile.ui.icons.LettaIconSizing
import com.letta.mobile.ui.icons.LettaIcons
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun AgentScaffoldTopBar(state: AgentScaffoldRuntimeState) {
    val params = state.params
    val searchUi = params.searchUi
    val showSearchField = searchUi.isChatSearchExpanded || state.uiState.isSearchActive

    TopAppBar(
        title = {
            if (showSearchField) {
                AgentScaffoldSearchTopBarTitle(
                    searchQuery = state.uiState.searchQuery,
                    onSearchQueryChange = params.viewModel::updateChatSearchQuery,
                    onClearSearch = params.viewModel::clearChatSearch,
                    chatSearchFocusRequester = searchUi.chatSearchFocusRequester,
                )
            } else {
                AgentScaffoldAgentTopBarTitle(
                    agentName = state.agentName,
                    screenTitle = state.screenTitle,
                    currentAgentIsFavorite = state.currentAgentIsFavorite,
                    currentAgentIsPinned = state.currentAgentIsPinned,
                    onAgentTitleClick = {
                        HapticEffects.contextClick(state.haptic, state.view)
                        params.viewModel.refreshAvailableAgents()
                        params.sheetVisibility.onShowAgentSwitcherChange(true)
                    },
                    onAgentTitleLongClick = {
                        HapticEffects.longPress(state.haptic)
                        params.viewModel.toggleCurrentAgentPinned()
                    },
                )
            }
        },
        modifier = Modifier.padding(top = with(LocalDensity.current) { WindowInsets.safeDrawing.getTop(this).toDp() }),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
        ),
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        scrollBehavior = state.scrollBehavior,
        actions = {
            AgentScaffoldTopBarActions(
                showSearchField = showSearchField,
                onSearchClick = {
                    HapticEffects.contextClick(state.haptic, state.view)
                    searchUi.onChatSearchExpandedChange(true)
                },
                onMenuClick = {
                    HapticEffects.contextClick(state.haptic, state.view)
                    state.projectBindings.refreshContextWindow()
                    state.scope.launch {
                        state.drawerState.open()
                        runCatching {
                            state.drawerConversationRepo.refreshConversations(params.viewModel.agentId)
                        }
                    }
                },
            )
        },
    )
}

@Composable
private fun AgentScaffoldSearchTopBarTitle(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    chatSearchFocusRequester: androidx.compose.ui.focus.FocusRequester,
) {
    LettaSearchBar(
        query = searchQuery,
        onQueryChange = onSearchQueryChange,
        onClear = onClearSearch,
        placeholder = stringResource(R.string.screen_conversations_search_hint),
        compact = true,
        searchIconContentDescription = null,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(chatSearchFocusRequester)
            .testTag(AgentScaffoldTestTags.CHAT_SEARCH_FIELD),
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgentScaffoldAgentTopBarTitle(
    agentName: String,
    screenTitle: String,
    currentAgentIsFavorite: Boolean,
    currentAgentIsPinned: Boolean,
    onAgentTitleClick: () -> Unit,
    onAgentTitleLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AgentScaffoldTestTags.CONVERSATION_PICKER_TRIGGER)
            .combinedClickable(
                onClick = onAgentTitleClick,
                onLongClick = onAgentTitleLongClick,
            )
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = agentName.ifBlank { screenTitle },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (currentAgentIsFavorite) {
            Icon(
                LettaIcons.Star,
                contentDescription = "Favorite agent",
                modifier = Modifier.size(LettaIconSizing.Inline),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        if (currentAgentIsPinned) {
            Icon(
                LettaIcons.Pin,
                contentDescription = "Pinned agent",
                modifier = Modifier.size(LettaIconSizing.Inline),
                tint = MaterialTheme.colorScheme.tertiary,
            )
        }
        Icon(
            LettaIcons.ArrowDropDown,
            contentDescription = "Switch agent",
            modifier = Modifier.size(LettaIconSizing.Inline),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AgentScaffoldTopBarActions(
    showSearchField: Boolean,
    onSearchClick: () -> Unit,
    onMenuClick: () -> Unit,
) {
    if (!showSearchField) {
        IconButton(onClick = onSearchClick) {
            Icon(LettaIcons.Search, contentDescription = "Search")
        }
    }
    IconButton(
        onClick = onMenuClick,
        modifier = Modifier.testTag(AgentScaffoldTestTags.MENU_BUTTON),
    ) {
        Icon(LettaIcons.Menu, "Menu")
    }
}
