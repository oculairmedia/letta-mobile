package com.letta.mobile.data.session

import com.letta.mobile.data.backend.RemoteRoute
import com.letta.mobile.data.backend.SessionRoute
import com.letta.mobile.data.repository.api.AgentRemoteSource
import com.letta.mobile.data.repository.api.ConversationCrudRemoteSource
import com.letta.mobile.data.repository.api.McpServerRemoteSource
import com.letta.mobile.data.repository.api.ModelRemoteSource
import com.letta.mobile.data.repository.api.PassageRemoteSource
import com.letta.mobile.data.repository.api.ProjectRemoteSource
import com.letta.mobile.data.repository.api.ProjectWorkRemoteSource
import com.letta.mobile.data.repository.api.ProviderRemoteSource
import com.letta.mobile.data.repository.api.RunRemoteSource
import com.letta.mobile.data.repository.api.ScheduleRemoteSource
import com.letta.mobile.data.repository.api.StepRemoteSource
import com.letta.mobile.data.repository.api.ToolRemoteSource

/**
 * letta-mobile-ckmub.4 — the Android backend seam.
 *
 * The app has one backend: a letta-code App Server, on this device (the embedded runtime) or on a
 * host reached over Iroh. Every session graph serves what that backend offers through its on-device
 * or Iroh sources. These remote sources are the extension point for another backend family (e.g.
 * a cloud backend, not offered for now): bind its sources here and the repositories, the shared
 * shell and every page pick it up unchanged.
 *
 * letta-mobile-by4vm.13: each source is a [RemoteRoute]. Production binds [None], where every route
 * is [RemoteRoute.Absent]: no reflection proxy stands in for a missing source, a repository falls
 * through to [RemoteRoute.source] only as the backstop, and [boundRoutes] tells the session's
 * [com.letta.mobile.data.backend.SessionCapabilities] what a bound family adds. Tests bind fakes.
 */
class SessionRemoteSources(
    val agents: RemoteRoute<AgentRemoteSource> = RemoteRoute.Absent(SessionRoute.AGENTS),
    val conversations: RemoteRoute<ConversationCrudRemoteSource> = RemoteRoute.Absent(SessionRoute.CONVERSATIONS),
    val mcpServers: RemoteRoute<McpServerRemoteSource> = RemoteRoute.Absent(SessionRoute.MCP_SERVERS),
    val models: RemoteRoute<ModelRemoteSource> = RemoteRoute.Absent(SessionRoute.MODELS),
    val passages: RemoteRoute<PassageRemoteSource> = RemoteRoute.Absent(SessionRoute.PASSAGES),
    val projects: RemoteRoute<ProjectRemoteSource> = RemoteRoute.Absent(SessionRoute.PROJECTS),
    val projectWork: RemoteRoute<ProjectWorkRemoteSource> = RemoteRoute.Absent(SessionRoute.PROJECT_WORK),
    val providers: RemoteRoute<ProviderRemoteSource> = RemoteRoute.Absent(SessionRoute.PROVIDERS),
    val runs: RemoteRoute<RunRemoteSource> = RemoteRoute.Absent(SessionRoute.RUNS),
    val schedules: RemoteRoute<ScheduleRemoteSource> = RemoteRoute.Absent(SessionRoute.SCHEDULES),
    /** Absent: no backend answers step queries, so the step repository reports none. */
    val steps: RemoteRoute<StepRemoteSource> = RemoteRoute.Absent(SessionRoute.STEPS),
    val tools: RemoteRoute<ToolRemoteSource> = RemoteRoute.Absent(SessionRoute.TOOLS),
) {
    /** The routes a bound source serves on top of the backend kind's own row of the matrix. */
    fun boundRoutes(): Set<SessionRoute> = mapOf(
        SessionRoute.AGENTS to agents,
        SessionRoute.CONVERSATIONS to conversations,
        SessionRoute.MCP_SERVERS to mcpServers,
        SessionRoute.MODELS to models,
        SessionRoute.PASSAGES to passages,
        SessionRoute.PROJECTS to projects,
        SessionRoute.PROJECT_WORK to projectWork,
        SessionRoute.PROVIDERS to providers,
        SessionRoute.RUNS to runs,
        SessionRoute.SCHEDULES to schedules,
        SessionRoute.STEPS to steps,
        SessionRoute.TOOLS to tools,
    ).filterValues { it.isBound }.keys

    companion object {
        /** The production binding: the App Server is the only backend, so no remote source. */
        val None: SessionRemoteSources = SessionRemoteSources()
    }
}
