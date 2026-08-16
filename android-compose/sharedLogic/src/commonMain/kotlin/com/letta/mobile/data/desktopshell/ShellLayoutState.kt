package com.letta.mobile.data.desktopshell

/**
 * Layout state for the desktop shell's collapsible capability/history
 * sidebar (Memory/Schedules/Channels/Skills/New chat/conversation history —
 * NOT the far-left agent rail, which has its own icon/expanded toggle).
 *
 * Platform-neutral by design (letta-mobile-o5m90): the collapse/resize rules
 * and the width-breakpoint decision live here so both desktop hosts share one
 * source of truth. Desktop supplies only rendering and a persistence binding.
 *
 * [collapsedPreference] is the user's *explicit* choice once one has been
 * made ([hasExplicitPreference]); until then, [ShellLayoutReducer] derives it
 * from [windowWidthDp] so the sidebar defaults to collapsed on a narrow
 * window without a prior launch ever having recorded a preference. Once the
 * user toggles or resizes, that derivation stops — widening the window back
 * out no longer overrides their choice.
 */
data class ShellLayoutState(
    val collapsedPreference: Boolean = false,
    val hasExplicitPreference: Boolean = false,
    val sidebarWidthDp: Float = ShellLayoutReducer.DEFAULT_SIDEBAR_WIDTH_DP,
    val windowWidthDp: Float = ShellLayoutReducer.DEFAULT_SIDEBAR_WIDTH_DP +
        ShellLayoutReducer.SIDEBAR_COLLAPSE_BREAKPOINT_DP,
) {
    /** True when the window is too narrow for the sidebar to coexist with a usable chat pane. */
    val isNarrowWindow: Boolean get() = windowWidthDp < ShellLayoutReducer.SIDEBAR_COLLAPSE_BREAKPOINT_DP

    /**
     * The sidebar is fully absent (not a narrow icon rail — see AC #3) unless
     * both the user's preference AND the window width allow it.
     */
    val isSidebarVisible: Boolean get() = !collapsedPreference && !isNarrowWindow
}

/** Pure events the desktop shell dispatches into [ShellLayoutReducer]. */
sealed interface ShellLayoutEvent {
    /** User pressed the sidebar toggle or its keyboard shortcut. */
    data object ToggleSidebar : ShellLayoutEvent

    /** Explicit collapse/expand request (e.g. from a command-palette action). */
    data class SetSidebarCollapsed(val collapsed: Boolean) : ShellLayoutEvent

    /** User dragged the sidebar's resize handle to a new width. */
    data class ResizeSidebar(val widthDp: Float) : ShellLayoutEvent

    /** The host window was resized; drives the narrow-width auto-collapse default. */
    data class WindowWidthChanged(val widthDp: Float) : ShellLayoutEvent
}

/**
 * Pure reducer for [ShellLayoutState]. No platform APIs, no side effects —
 * desktop hosts call [reduce] and persist the result themselves.
 */
object ShellLayoutReducer {
    /** Matches the sidebar's existing fixed width (DesktopAgentSidebarUi.kt). */
    const val DEFAULT_SIDEBAR_WIDTH_DP = 231f
    const val MIN_SIDEBAR_WIDTH_DP = 200f
    const val MAX_SIDEBAR_WIDTH_DP = 360f

    /**
     * Below this window width the sidebar is not viable alongside chat.
     * Matches the desktop shell's existing CompactShellWidthBreakpoint.
     */
    const val SIDEBAR_COLLAPSE_BREAKPOINT_DP = 840f

    fun reduce(state: ShellLayoutState, event: ShellLayoutEvent): ShellLayoutState = when (event) {
        ShellLayoutEvent.ToggleSidebar -> if (state.isNarrowWindow) {
            state
        } else {
            state.copy(
                collapsedPreference = !state.collapsedPreference,
                hasExplicitPreference = true,
            )
        }
        is ShellLayoutEvent.SetSidebarCollapsed -> state.copy(
            collapsedPreference = event.collapsed,
            hasExplicitPreference = true,
        )
        is ShellLayoutEvent.ResizeSidebar -> state.copy(
            sidebarWidthDp = event.widthDp.coerceIn(MIN_SIDEBAR_WIDTH_DP, MAX_SIDEBAR_WIDTH_DP),
            hasExplicitPreference = true,
        )
        is ShellLayoutEvent.WindowWidthChanged -> {
            val next = state.copy(windowWidthDp = event.widthDp)
            if (state.hasExplicitPreference) {
                next
            } else {
                next.copy(collapsedPreference = defaultCollapsedForWidth(event.widthDp))
            }
        }
    }

    /** Default collapse rule used until the user has made an explicit choice. */
    fun defaultCollapsedForWidth(widthDp: Float): Boolean = widthDp < SIDEBAR_COLLAPSE_BREAKPOINT_DP
}
