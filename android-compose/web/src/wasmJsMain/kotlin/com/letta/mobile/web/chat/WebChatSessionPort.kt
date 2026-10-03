package com.letta.mobile.web.chat

import com.letta.mobile.data.timeline.TimelineSyncLoop
import com.letta.mobile.data.timeline.TimelineTransport
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.session.ChatActions
import com.letta.mobile.ui.chat.session.ChatComposerUiState
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ChatSurfaceCapabilities
import com.letta.mobile.ui.chat.session.TimelineChatRunControls
import com.letta.mobile.ui.chat.session.TimelineChatSessionOptions
import com.letta.mobile.ui.chat.session.TimelineChatSessionPort
import com.letta.mobile.ui.chat.session.TimelineChatTarget
import com.letta.mobile.ui.chat.session.TimelineSyncLoopSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/** What the live App Server session hands the web's chat page for one conversation. */
class WebChatBinding(
    val target: TimelineChatTarget,
    /** The conversation's timeline source: the App Server socket (WebSocket or Iroh). */
    val transport: TimelineTransport,
    val controls: TimelineChatRunControls,
)

/**
 * letta-mobile-o4ygk.4.5: the web client's [ChatSessionPort], the way Android's
 * `AdminChatSessionPort` and desktop's `DesktopChatSessionPort` bind theirs.
 *
 * The web has no conversation owner of its own, so this only binds: the shared timeline loop runs
 * over [WebChatBinding.transport], and the commonMain [TimelineChatSessionPort] presents it (the
 * same timeline presenter, composer policy and optimistic send every client uses). Build one per
 * conversation in a scope that ends with it, and [close] it when the page moves on.
 *
 * The timeline lives in memory: a reload hydrates it again from the server (the web has no
 * persistent timeline store or paged history yet: letta-mobile-o4ygk.4.7).
 */
class WebChatSessionPort(
    binding: WebChatBinding,
    scope: CoroutineScope,
) : ChatSessionPort {
    private val loop = TimelineSyncLoop(
        messageApi = binding.transport,
        conversationId = binding.target.conversationId,
        scope = scope,
        logTag = LOG_TAG,
        agentId = binding.target.agentId,
    )
    private val port = TimelineChatSessionPort(
        session = TimelineSyncLoopSession(loop),
        target = binding.target,
        scope = scope,
        options = TimelineChatSessionOptions(controls = binding.controls),
    )

    /** The conversation this port serves; the canvas docked under the page is that conversation's board. */
    val target: TimelineChatTarget = binding.target

    override val uiState: StateFlow<ChatUiState> get() = port.uiState
    override val composer: StateFlow<ChatComposerUiState> get() = port.composer
    override val actions: ChatActions get() = port.actions
    override val capabilities: StateFlow<ChatSurfaceCapabilities> get() = port.capabilities

    /** Stops the conversation's timeline (its stream subscriber and send pipeline). */
    fun close() = loop.close()

    private companion object {
        const val LOG_TAG = "WebChat"
    }
}
