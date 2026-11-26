package com.letta.mobile.feature.chat.screen.shared

import androidx.compose.runtime.Composable
import com.letta.mobile.feature.chat.screen.AdminChatViewModel
import com.letta.mobile.feature.chat.screen.ChatPagingPresentation
import com.letta.mobile.feature.chat.screen.ChatScreenNavigationCallbacks
import com.letta.mobile.feature.chat.screen.rememberChatScreenSubagentBarState

/** Common Android page fixture; assertions live independently of the host's parameter wiring. */
@Composable
internal fun CanonicalParityPage(viewModel: AdminChatViewModel, paging: ChatPagingPresentation) {
    SharedChatPage(
        SharedChatPageParams(
            viewModel = viewModel,
            navigation = ChatScreenNavigationCallbacks(),
            chatMode = "interactive",
            fontScale = 1f,
            hapticsEnabled = false,
            pagingPresentation = paging,
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
