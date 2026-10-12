package com.letta.mobile.feature.chat.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.letta.mobile.feature.chat.R
import com.letta.mobile.ui.chat.AgentIdentity
import com.letta.mobile.ui.chat.AgentIdentityPill
import com.letta.mobile.ui.haptics.HapticEffects
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.shell.header.HeaderAction
import com.letta.mobile.ui.shell.header.HeaderControls
import com.letta.mobile.ui.shell.header.HeaderTitle
import com.letta.mobile.ui.shell.header.SearchSlot
import com.letta.mobile.ui.shell.header.ShellHeader
import com.letta.mobile.ui.shell.header.ShellHeaderDefaults
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlinx.coroutines.launch

/**
 * The chat screen's top chrome (hard cut S9): the shared [ShellHeader] with the agent pill, the
 * agent's message search ([ChatMessageSearch]) and the drawer menu; or, while the shared page's
 * phone canvas mode keeps the board's top clear (letta-mobile-bglj6.1), the agent pill alone in the
 * same spot (letta-mobile-bglj6.1.22). Both draw the one [AgentIdentityPill].
 */
@Composable
internal fun AgentScaffoldTopChrome(state: AgentScaffoldRuntimeState, headerHidden: Boolean) {
    val params = state.params
    AgentScaffoldTopChromeLayout(
        headerHidden = headerHidden,
        identity = AgentIdentity(
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
        search = rememberChatMessageSearchSlot(state),
        onMenuClick = {
            HapticEffects.contextClick(state.haptic, state.view)
            state.scope.launch {
                state.drawerState.open()
                runCatching {
                    state.drawerConversationRepo.refreshConversations(params.viewModel.agentId)
                }
            }
        },
    )
}

/** The header's search over this agent's messages; a picked hit opens its conversation. */
@Composable
private fun rememberChatMessageSearchSlot(state: AgentScaffoldRuntimeState): SearchSlot {
    val params = state.params
    val ui = state.uiState
    val placeholder = stringResource(R.string.screen_conversations_search_hint)
    val sections = remember(ui.searchQuery, ui.searchResults, state.drawerConversations, state.conversationId) {
        ChatMessageSearch.sections(ui.searchQuery, ui.searchResults, state.drawerConversations, state.conversationId)
    }
    return SearchSlot(
        query = ui.searchQuery,
        onQuery = params.viewModel::updateChatSearchQuery,
        config = ChatMessageSearch.config(placeholder, ui.isSearching),
        sections = sections,
        onRowSelected = { row ->
            val result = ChatMessageSearch.resultFor(row.id, ui.searchResults)
            params.viewModel.clearChatSearch()
            result?.conversationId?.let { conversationId ->
                params.navigation.onSwitchConversation?.invoke(
                    state.agentIdValue,
                    conversationId,
                    state.agentName.takeIf { it.isNotBlank() },
                )
            }
        },
        onDismiss = params.viewModel::clearChatSearch,
        // `/search` opens the pane.
        openSignal = params.searchUi.openSignal,
    )
}

/** [AgentScaffoldTopChrome] without the runtime state, so tests can draw both of its modes. */
@Composable
internal fun AgentScaffoldTopChromeLayout(
    headerHidden: Boolean,
    identity: AgentIdentity,
    search: SearchSlot?,
    onMenuClick: () -> Unit,
) {
    val reducedMotion = LocalReducedMotion.current
    val topInset = with(LocalDensity.current) { WindowInsets.safeDrawing.getTop(this).toDp() }
    Box {
        AnimatedVisibility(
            visible = !headerHidden,
            enter = if (reducedMotion) EnterTransition.None else fadeIn(),
            exit = if (reducedMotion) ExitTransition.None else fadeOut(),
        ) {
            ShellHeader(
                title = HeaderTitle.Custom { AgentIdentityPill(identity) },
                search = search,
                controls = HeaderControls(actions = listOf(HeaderAction.Custom { AgentScaffoldMenuButton(onMenuClick) })),
                modifier = Modifier
                    .padding(top = topInset)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    // The scaffold's top bar slot does not bound the height; the header's free
                    // lane fills it, so pin the phone app bar's height.
                    .height(ShellHeaderDefaults.TouchMinHeight)
                    .testTag(AgentScaffoldTestTags.HEADER),
            )
        }
        AnimatedVisibility(
            visible = headerHidden,
            enter = if (reducedMotion) EnterTransition.None else fadeIn(),
            exit = if (reducedMotion) ExitTransition.None else fadeOut(),
        ) {
            AgentScaffoldCanvasIdentityPill(identity, topInset)
        }
    }
}

/**
 * letta-mobile-bglj6.1.22: the agent at a glance while the phone canvas mode hides the header -
 * the header's own pill, alone where the header drew it: the same top inset, the header's height
 * and its title inset, so switching modes leaves the pill in place. Only the pill takes touches;
 * the rest of the board's top stays clear. The menu stays in the board's menu.
 */
@Composable
private fun AgentScaffoldCanvasIdentityPill(identity: AgentIdentity, topInset: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .padding(top = topInset)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
            .height(ShellHeaderDefaults.TouchMinHeight)
            // ShellHeader's leading inset when it has no nav affordance.
            .padding(start = LettaDimens.Space.lg)
            .testTag(AgentScaffoldTestTags.CANVAS_IDENTITY_PILL),
        contentAlignment = Alignment.CenterStart,
    ) {
        AgentIdentityPill(identity)
    }
}

@Composable
private fun AgentScaffoldMenuButton(onMenuClick: () -> Unit) {
    IconButton(
        onClick = onMenuClick,
        modifier = Modifier.testTag(AgentScaffoldTestTags.MENU_BUTTON),
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = Color.Black,
            contentColor = Color.White,
        ),
    ) {
        Icon(LettaIcons.Menu, "Menu")
    }
}
