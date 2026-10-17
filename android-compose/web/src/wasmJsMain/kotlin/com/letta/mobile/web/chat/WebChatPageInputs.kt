package com.letta.mobile.web.chat

import com.letta.mobile.data.canvas.CanvasDocumentStore
import com.letta.mobile.data.canvas.CanvasOpLog
import com.letta.mobile.data.canvas.CanvasRelayClient
import com.letta.mobile.ui.chat.session.ChatDockGeometry
import com.letta.mobile.ui.chat.session.SubagentViewSessions
import com.letta.mobile.ui.chat.surface.LayoutPrefStorage

/**
 * The board docked under the conversation: where it and its op log are kept, the shared relay that
 * syncs its edits and cursors (letta-mobile-ckmub.17.9), the dock's placement, and whether the page
 * opens on it.
 */
internal data class WebChatCanvas(
    val store: CanvasDocumentStore,
    val opLog: CanvasOpLog,
    val relay: CanvasRelayClient,
    val dockGeometry: LayoutPrefStorage<ChatDockGeometry>,
    val openOnCanvas: Boolean,
)

/** Where the page's host affordances go in the web shell. */
internal data class WebChatPageNavigation(
    /** The agent's mascot beside the composer: show the agent list (the sidebar). */
    val openAgentPane: () -> Unit,
    /** Agent display names for provenance labels. */
    val agentNamesById: Map<String, String> = emptyMap(),
    /** letta-mobile-zxk2n.8: where a subagent's conversation is read for the dual view; null leaves it off. */
    val subagentSessions: SubagentViewSessions? = null,
)
