package com.letta.mobile.data.session

import com.letta.mobile.data.backend.noAppServerRoute
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
 * a cloud backend, not offered for now): implement the interfaces, bind the bundle for that
 * [com.letta.mobile.data.model.BackendKind] in [SessionGraphAssembler], and the repositories, the
 * shared shell and every page pick it up unchanged.
 *
 * Production binds [None]: every call fails with
 * [com.letta.mobile.data.backend.NoAppServerRouteException] rather than reaching for a Letta REST
 * server, which no longer exists in the app. Tests bind fakes to drive the repositories.
 */
class SessionRemoteSources(
    val agents: AgentRemoteSource = noAppServerRoute(),
    val conversations: ConversationCrudRemoteSource = noAppServerRoute(),
    val mcpServers: McpServerRemoteSource = noAppServerRoute(),
    val models: ModelRemoteSource = noAppServerRoute(),
    val passages: PassageRemoteSource = noAppServerRoute(),
    val projects: ProjectRemoteSource = noAppServerRoute(),
    val projectWork: ProjectWorkRemoteSource = noAppServerRoute(),
    val providers: ProviderRemoteSource = noAppServerRoute(),
    val runs: RunRemoteSource = noAppServerRoute(),
    val schedules: ScheduleRemoteSource = noAppServerRoute(),
    /** Null: no backend answers step queries, so the step repository reports none. */
    val steps: StepRemoteSource? = null,
    val tools: ToolRemoteSource = noAppServerRoute(),
) {
    companion object {
        /** The production binding: the App Server is the only backend, so no remote source. */
        val None: SessionRemoteSources = SessionRemoteSources()
    }
}
