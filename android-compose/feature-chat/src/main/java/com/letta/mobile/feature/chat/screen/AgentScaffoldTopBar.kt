package com.letta.mobile.feature.chat.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.letta.mobile.feature.chat.R
import com.letta.mobile.ui.chat.AgentIdentityPill
import com.letta.mobile.ui.chat.AgentPillSurface
import com.letta.mobile.ui.components.LettaSearchBar
import com.letta.mobile.ui.haptics.HapticEffects
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.coroutines.launch

/**
 * The chat screen's top chrome: the header (agent pill and menu) or, while the shared page's phone
 * canvas mode keeps the board's top clear (letta-mobile-bglj6.1), the agent pill alone in the same
 * spot (letta-mobile-bglj6.1.22). Both draw the one [AgentIdentityPill].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentScaffoldTopChrome(state: AgentScaffoldRuntimeState, headerHidden: Boolean) {
    val params = state.params
    val searchUi = params.searchUi
    val showSearchField = searchUi.isChatSearchExpanded || state.uiState.isSearchActive
    AgentScaffoldTopChromeLayout(
        headerHidden = headerHidden,
        identity = AgentScaffoldIdentity(
            agentId = state.agentIdValue,
            name = state.agentName.ifBlank { state.screenTitle },
            isFavorite = state.currentAgentIsFavorite,
            isPinned = state.currentAgentIsPinned,
            onClick = {
                HapticEffects.contextClick(state.haptic, state.view)
                params.viewModel.refreshAvailableAgents()
                params.sheetVisibility.onShowAgentSwitcherChange(true)
            },
            onLongClick = {
                HapticEffects.longPress(state.haptic)
                params.viewModel.toggleCurrentAgentPinned()
            },
        ),
        searchField = if (showSearchField) {
            {
                AgentScaffoldSearchTopBarTitle(
                    searchQuery = state.uiState.searchQuery,
                    onSearchQueryChange = params.viewModel::updateChatSearchQuery,
                    onClearSearch = params.viewModel::clearChatSearch,
                    chatSearchFocusRequester = searchUi.chatSearchFocusRequester,
                )
            }
        } else {
            null
        },
        onMenuClick = {
            HapticEffects.contextClick(state.haptic, state.view)
            // letta-mobile-0ofhc: the composer's context chip loads from the streamed
            // reading on its own; opening the drawer is no longer what feeds it.
            state.scope.launch {
                state.drawerState.open()
                runCatching {
                    state.drawerConversationRepo.refreshConversations(params.viewModel.agentId)
                }
            }
        },
        scrollBehavior = state.scrollBehavior,
    )
}

/** What the agent pill shows and does, built once for both of its places. */
internal data class AgentScaffoldIdentity(
    val agentId: String,
    val name: String,
    val isFavorite: Boolean,
    val isPinned: Boolean,
    val onClick: () -> Unit,
    val onLongClick: () -> Unit,
)

/** [AgentScaffoldTopChrome] without the runtime state, so tests can draw both of its modes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentScaffoldTopChromeLayout(
    headerHidden: Boolean,
    identity: AgentScaffoldIdentity,
    searchField: (@Composable () -> Unit)?,
    onMenuClick: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior?,
) {
    val reducedMotion = LocalReducedMotion.current
    Box {
        AnimatedVisibility(
            visible = !headerHidden,
            enter = if (reducedMotion) EnterTransition.None else fadeIn(),
            exit = if (reducedMotion) ExitTransition.None else fadeOut(),
        ) {
            AgentScaffoldHeader(identity, searchField, onMenuClick, scrollBehavior)
        }
        AnimatedVisibility(
            visible = headerHidden,
            enter = if (reducedMotion) EnterTransition.None else fadeIn(),
            exit = if (reducedMotion) ExitTransition.None else fadeOut(),
        ) {
            AgentScaffoldCanvasIdentityPill(identity)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentScaffoldHeader(
    identity: AgentScaffoldIdentity,
    searchField: (@Composable () -> Unit)?,
    onMenuClick: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior?,
) {
    TopAppBar(
        title = {
            if (searchField != null) AgentPillSurface { searchField() } else AgentScaffoldIdentityPill(identity)
        },
        modifier = Modifier
            .padding(top = with(LocalDensity.current) { WindowInsets.safeDrawing.getTop(this).toDp() })
            .testTag(AgentScaffoldTestTags.HEADER),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
        ),
        windowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal),
        scrollBehavior = scrollBehavior,
        actions = { AgentScaffoldTopBarActions(onMenuClick = onMenuClick) },
    )
}

/**
 * letta-mobile-bglj6.1.22: the agent at a glance while the phone canvas mode hides the header -
 * the header's own pill, alone where the header drew it: the same top inset, the app bar's height
 * and its title inset, so switching modes leaves the pill in place. Only the pill takes touches;
 * the rest of the board's top stays clear. The menu stays in the board's menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentScaffoldCanvasIdentityPill(identity: AgentScaffoldIdentity) {
    Box(
        Modifier
            .padding(top = with(LocalDensity.current) { WindowInsets.safeDrawing.getTop(this).toDp() })
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .height(TopAppBarDefaults.TopAppBarExpandedHeight)
            .padding(start = AppBarTitleInset)
            .testTag(AgentScaffoldTestTags.CANVAS_IDENTITY_PILL),
        contentAlignment = Alignment.CenterStart,
    ) {
        AgentScaffoldIdentityPill(identity)
    }
}

@Composable
private fun AgentScaffoldIdentityPill(identity: AgentScaffoldIdentity) {
    AgentIdentityPill(
        agentId = identity.agentId,
        name = identity.name,
        isFavorite = identity.isFavorite,
        isPinned = identity.isPinned,
        onClick = identity.onClick,
        onLongClick = identity.onLongClick,
    )
}

/**
 * Where Material's small app bar starts its title when it has no navigation icon (16dp in the
 * current Material 3; AgentScaffoldTopChromeUiTest pins the two placements together).
 */
private val AppBarTitleInset = 16.dp

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

@Composable
private fun AgentScaffoldTopBarActions(
    onMenuClick: () -> Unit,
) {
    IconButton(
        onClick = onMenuClick,
        modifier = Modifier.testTag(AgentScaffoldTestTags.MENU_BUTTON),
        colors = androidx.compose.material3.IconButtonDefaults.iconButtonColors(
            containerColor = Color.Black,
            contentColor = Color.White,
        ),
    ) {
        Icon(LettaIcons.Menu, "Menu")
    }
}
