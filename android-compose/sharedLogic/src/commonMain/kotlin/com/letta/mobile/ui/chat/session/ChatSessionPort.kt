package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.chat.recompile.RecompileStatus
import com.letta.mobile.data.runtime.RuntimeLiveStatus
import com.letta.mobile.ui.chat.render.ChatUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one seam between a conversation owner and the shared chat page.
 *
 * [TimelineChatSessionPort] binds the shared timeline and composer to this contract.
 * Hosts supply the conversation and platform bindings; shared page presentations
 * consume the same state and actions.
 *
 * The docked (canvas), full-screen and floating presentations all bind to the SAME port
 * instance, which is what makes the draft, the queued follow-ups and the run shared across
 * modes rather than copied between them.
 */
interface ChatSessionPort {
    /** The conversation timeline and run state. */
    val uiState: StateFlow<ChatUiState>

    /** The composer draft and its chrome. Separate so keystrokes don't recompose the timeline. */
    val composer: StateFlow<ChatComposerUiState>

    /** User-intent handlers for this conversation, including composer, timeline and run controls. */
    val actions: ChatActions

    /**
     * What this owner supports. A flow, because it can change while the page is open (desktop:
     * the gateway's approval support, the canonical paged route).
     */
    val capabilities: StateFlow<ChatSurfaceCapabilities>
        get() = DefaultCapabilities

    /**
     * The visible conversation's live status (loop phase, retry, server
     * notices, server-side commands). Separate from [uiState] so its ticks never recompose the
     * timeline. An owner that cannot observe it leaves the default, and the status line stays hidden.
     */
    val liveStatus: StateFlow<RuntimeLiveStatus>
        get() = DefaultLiveStatus

    /**
     *  (G92): where the last `/recompile` stands; the page shows it as a
     * snackbar (queued until the answer, then the answer). An owner without `/recompile` leaves it idle.
     */
    val recompileStatus: StateFlow<RecompileStatus>
        get() = DefaultRecompileStatus
}

private val DefaultRecompileStatus: StateFlow<RecompileStatus> =
    MutableStateFlow<RecompileStatus>(RecompileStatus.Idle).asStateFlow()

private val DefaultCapabilities: StateFlow<ChatSurfaceCapabilities> =
    MutableStateFlow(ChatSurfaceCapabilities.Default).asStateFlow()

private val DefaultLiveStatus: StateFlow<RuntimeLiveStatus> =
    MutableStateFlow(RuntimeLiveStatus.Idle).asStateFlow()
