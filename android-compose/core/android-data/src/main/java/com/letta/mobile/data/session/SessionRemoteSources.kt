package com.letta.mobile.data.session

import com.letta.mobile.data.backend.RemoteRoute
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.repository.api.AgentRemoteSource
import com.letta.mobile.data.repository.api.ConversationCrudRemoteSource
import com.letta.mobile.data.repository.api.ScheduleRemoteSource
import com.letta.mobile.data.session.graph.AdminRemoteRoutes

/**
 * The Android backend seam.
 *
 * The app has one backend: a letta-code App Server, on this device (the embedded runtime) or on a
 * host reached over Iroh. Every session graph serves what that backend offers through its on-device
 * or Iroh sources. These remote sources are the extension point for another backend family (e.g.
 * a cloud backend, not offered for now): bind its sources here and the repositories, the shared
 * shell and every page pick it up unchanged.
 *
 * Each source is a [RemoteRoute]. Production binds [None], where every route is
 * [RemoteRoute.Absent]: no reflection proxy stands in for a missing source, a repository falls
 * through to [RemoteRoute.source] only as the backstop, and [boundRoutes] tells the session's
 * [com.letta.mobile.data.backend.SessionCapabilities] what a bound family adds. Tests bind fakes.
 */
class SessionRemoteSources(
    val agents: RemoteRoute<AgentRemoteSource> = RemoteRoute.Absent(SessionRoute.AGENTS),
    val conversations: RemoteRoute<ConversationCrudRemoteSource> = RemoteRoute.Absent(SessionRoute.CONVERSATIONS),
    val admin: AdminRemoteRoutes = AdminRemoteRoutes(),
    val schedules: RemoteRoute<ScheduleRemoteSource> = RemoteRoute.Absent(SessionRoute.SCHEDULES),
) {
    /** The routes a bound source serves on top of the backend kind's own row of the matrix. */
    fun boundRoutes(): Set<SessionRoute> = mapOf(
        SessionRoute.AGENTS to agents,
        SessionRoute.CONVERSATIONS to conversations,
        SessionRoute.BLOCKS to admin.blocks,
        SessionRoute.MCP_SERVERS to admin.mcpServers,
        SessionRoute.MODELS to admin.models,
        SessionRoute.PROJECTS to admin.projects,
        SessionRoute.PROVIDERS to admin.providers,
        SessionRoute.RUNS to admin.runs,
        SessionRoute.SCHEDULES to schedules,
        SessionRoute.TOOLS to admin.tools,
    ).filterValues { it.isBound }.keys

    companion object {
        /** The production binding: the App Server is the only backend, so no remote source. */
        val None: SessionRemoteSources = SessionRemoteSources()
    }
}
