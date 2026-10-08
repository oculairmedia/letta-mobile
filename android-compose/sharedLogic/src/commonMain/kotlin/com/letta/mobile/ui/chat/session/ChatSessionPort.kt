package com.letta.mobile.ui.chat.session

import com.letta.mobile.data.runtime.RuntimeLiveStatus
import com.letta.mobile.ui.chat.render.ChatUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * letta-mobile-bglj6.1: the one seam between a conversation owner and the shared chat page.
 *
 * Each platform's existing owner implements it: Android's `AdminChatViewModel` and
 * desktop's `DesktopChatController`. A later phase replaces both with one commonMain
 * presenter that implements the same port, so the page does not change when that lands.
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

    val actions: ChatActions

    /**
     * What this owner supports. A flow, because it can change while the page is open (desktop:
     * the gateway's approval support, the canonical paged route).
     */
    val capabilities: StateFlow<ChatSurfaceCapabilities>
        get() = DefaultCapabilities

    /**
     * letta-mobile-bzvro.7/.8: the visible conversation's live status (loop phase, retry, server
     * notices, server-side commands). Separate from [uiState] so its ticks never recompose the
     * timeline. An owner that cannot observe it leaves the default, and the status line stays hidden.
     */
    val liveStatus: StateFlow<RuntimeLiveStatus>
        get() = DefaultLiveStatus
}

private val DefaultCapabilities: StateFlow<ChatSurfaceCapabilities> =
    MutableStateFlow(ChatSurfaceCapabilities.Default).asStateFlow()

private val DefaultLiveStatus: StateFlow<RuntimeLiveStatus> =
    MutableStateFlow(RuntimeLiveStatus.Idle).asStateFlow()
