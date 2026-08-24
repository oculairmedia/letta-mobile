package com.letta.mobile.data.transport.appserver

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Routes one logical client surface across two independent App Server `/ws`
 * clients without reviving the removed `?channel=control|stream` protocol.
 *
 * [runtime] exclusively owns runtime subscriptions, turns, callbacks, channel
 * ingress, mutations, and the event stream. [admin] remains unsubscribed and
 * handles idempotent management reads. A failed or oversized admin
 * response can therefore tear down the admin connection without cancelling a
 * live turn, while mutation-side invalidation events stay on the observed lane.
 *
 * Requests never fail over between lanes: request correlation and socket-bound
 * ownership remain local to the client that sent the command.
 */
class DualLaneAppServerClient(
    private val runtime: AppServerClient,
    private val admin: AppServerClient,
) : AppServerClient {
    override val events: Flow<AppServerReceivedFrame> = runtime.events
    override val isConnected: Flow<Boolean> = runtime.isConnected
    override val serverInfo: StateFlow<AppServerInfoData?> = runtime.serverInfo

    override suspend fun auth(command: AppServerCommand.Auth) = runtime.auth(command)
    override suspend fun appServerInfo(command: AppServerCommand.AppServerInfo) = runtime.appServerInfo(command)
    override suspend fun runtimeStart(command: AppServerCommand.RuntimeStart) = runtime.runtimeStart(command)
    override suspend fun input(command: AppServerCommand.Input) = runtime.input(command)
    override suspend fun sync(command: AppServerCommand.Sync) = runtime.sync(command)
    override suspend fun abort(command: AppServerCommand.AbortMessage) = runtime.abort(command)
    override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) =
        runtime.sendExternalToolResponse(command)

    // admin_rpc is an extension with method-dependent read/write semantics, so
    // it stays on the runtime lane unless and until its methods are typed.
    override suspend fun adminRpc(command: AppServerCommand.AdminRpc) = runtime.adminRpc(command)
    override suspend fun agentList(command: AppServerCommand.AgentList) = admin.agentList(command)
    override suspend fun agentRetrieve(command: AppServerCommand.AgentRetrieve) = admin.agentRetrieve(command)
    override suspend fun agentCreate(command: AppServerCommand.AgentCreate) = runtime.agentCreate(command)
    override suspend fun agentUpdate(command: AppServerCommand.AgentUpdate) = runtime.agentUpdate(command)
    override suspend fun agentDelete(command: AppServerCommand.AgentDelete) = runtime.agentDelete(command)
    override suspend fun conversationList(command: AppServerCommand.ConversationList) = admin.conversationList(command)
    override suspend fun conversationRetrieve(command: AppServerCommand.ConversationRetrieve) =
        admin.conversationRetrieve(command)
    override suspend fun conversationCreate(command: AppServerCommand.ConversationCreate) =
        runtime.conversationCreate(command)
    override suspend fun conversationUpdate(command: AppServerCommand.ConversationUpdate) =
        runtime.conversationUpdate(command)
    override suspend fun conversationMessagesList(command: AppServerCommand.ConversationMessagesList) =
        admin.conversationMessagesList(command)
    override suspend fun conversationCompact(command: AppServerCommand.ConversationCompact) =
        runtime.conversationCompact(command)
    override suspend fun listModels(command: AppServerCommand.ListModels) = admin.listModels(command)
    override suspend fun skillEnable(command: AppServerCommand.SkillEnable) = runtime.skillEnable(command)
    override suspend fun skillDisable(command: AppServerCommand.SkillDisable) = runtime.skillDisable(command)
    override suspend fun writeMemoryFile(command: AppServerCommand.WriteMemoryFile) = runtime.writeMemoryFile(command)
    override suspend fun cronList(command: AppServerCommand.CronList) = admin.cronList(command)
    override suspend fun cronAdd(command: AppServerCommand.CronAdd) = runtime.cronAdd(command)
    override suspend fun cronGet(command: AppServerCommand.CronGet) = admin.cronGet(command)
    override suspend fun cronRuns(command: AppServerCommand.CronRuns) = admin.cronRuns(command)
    override suspend fun cronTrigger(command: AppServerCommand.CronTrigger) = runtime.cronTrigger(command)
    override suspend fun cronUpdate(command: AppServerCommand.CronUpdate) = runtime.cronUpdate(command)
    override suspend fun cronDelete(command: AppServerCommand.CronDelete) = runtime.cronDelete(command)
    override suspend fun cronDeleteAll(command: AppServerCommand.CronDeleteAll) = runtime.cronDeleteAll(command)
    override suspend fun getReflectionSettings(command: AppServerCommand.GetReflectionSettings) =
        admin.getReflectionSettings(command)
    override suspend fun setReflectionSettings(command: AppServerCommand.SetReflectionSettings) =
        runtime.setReflectionSettings(command)
    override suspend fun getCwdMap(command: AppServerCommand.GetCwdMap) = admin.getCwdMap(command)

    // Channel discovery and mutation stay with the runtime lane because
    // channel_start binds ingress to the connection that issued it.
    override suspend fun channelsList(command: AppServerCommand.ChannelsList) = runtime.channelsList(command)
    override suspend fun channelAccountsList(command: AppServerCommand.ChannelAccountsList) =
        runtime.channelAccountsList(command)
    override suspend fun channelStart(command: AppServerCommand.ChannelStart) = runtime.channelStart(command)
    override suspend fun channelAccountUpdate(command: AppServerCommand.ChannelAccountUpdate) =
        runtime.channelAccountUpdate(command)
}
