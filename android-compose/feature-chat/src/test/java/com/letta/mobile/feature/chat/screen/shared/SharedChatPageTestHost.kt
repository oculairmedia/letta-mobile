package com.letta.mobile.feature.chat.screen.shared

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.letta.mobile.feature.chat.screen.AdminChatViewModel
import com.letta.mobile.feature.chat.screen.ChatPagingPresentation
import com.letta.mobile.feature.chat.screen.ChatScreenNavigationCallbacks
import com.letta.mobile.feature.chat.screen.rememberChatScreenSubagentBarState

/** Common Android page fixture; assertions live independently of the host's parameter wiring. */
@Composable
internal fun CanonicalParityPage(viewModel: AdminChatViewModel, paging: ChatPagingPresentation) {
    val navigation = remember { ChatScreenNavigationCallbacks() }
    SharedChatPage(
        SharedChatPageParams(
            session = rememberAdminChatPageSession(viewModel, navigation),
            navigation = navigation,
            chatMode = "interactive",
            fontScale = 1f,
            hapticsEnabled = false,
            pagedTimeline = paging.canonical,
            openOnCanvas = false,
            subagents = SharedChatSubagentInputs(
                source = viewModel.activeSubagentSource,
                selfTodoSource = viewModel.selfTodoSource,
                barState = rememberChatScreenSubagentBarState(
                    viewModel.activeSubagentSource, viewModel.selfTodoSource, viewModel.conversationId?.value,
                ),
            ),
        ),
    )
}
