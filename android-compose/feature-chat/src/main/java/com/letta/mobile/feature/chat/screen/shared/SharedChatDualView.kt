package com.letta.mobile.feature.chat.screen.shared

import com.letta.mobile.data.subagents.SubagentView
import com.letta.mobile.feature.chat.screen.ChatScreenNavigationCallbacks
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.subagents.SubagentDualViewBinding
import com.letta.mobile.ui.chat.subagents.SubagentDualViewOptions
import com.letta.mobile.ui.chat.subagents.conversationOpener
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance

/*
 * letta-mobile-zxk2n.8: Android's binding of the shared subagent dual view. The view, its read-only
 * pane and its openers are the sharedUI ones; this only maps the page's navigation onto them.
 */

/** "Open the subagent's full conversation": the subagent conversation route. */
internal fun SharedChatPageParams.fullSubagentView(): ((SubagentView) -> Unit)? =
    navigation.onViewSubagentConversation?.let { open -> { view -> open(view.agentId.value, view.conversationId.value) } }

/** The page's navigation with "view conversation" (rings, todo sheet, roster) opening [dualView], when there is one. */
internal fun SharedChatPageParams.dualNavigation(dualView: SubagentDualViewBinding?): ChatScreenNavigationCallbacks {
    val open = dualView?.holder?.conversationOpener { subagents.source.rosterEntries.value } ?: return navigation
    return navigation.copy(onViewSubagentConversation = open)
}

/** How the subagent's pane is drawn: the page's own appearance and host, below its floating header. */
internal fun SharedChatPageParams.dualViewOptions(appearance: ChatSurfaceAppearance, host: ChatSurfaceHost) =
    SubagentDualViewOptions(appearance = appearance, host = host, topInset = topChromeInset)