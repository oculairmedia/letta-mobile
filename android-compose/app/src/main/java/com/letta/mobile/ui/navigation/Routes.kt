package com.letta.mobile.ui.navigation

import kotlinx.serialization.Serializable

/** letta-mobile-ckmub.2: the shared shell workspace, the app's page; the routes below are Android's own screens. */
@Serializable data object ShellWorkspaceRoute
@Serializable data object ConfigListRoute
@Serializable data object SystemAccessRoute
@Serializable data object PairingScanRoute
@Serializable data object PairingInviteRoute
@Serializable data object CanvasDebugRoute
@Serializable
data class CanvasRoute(
    val canvasId: String = "",
    val conversationId: String? = null,
    /** The conversation's agent, granted write access on a canvas this route creates. */
    val agentId: String? = null,
    /** The chat screen a share lands in; without one there is nowhere to share to. */
    val shareRecipient: String? = null,
)
