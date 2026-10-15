package com.letta.mobile.data.controller.reconnect

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerWorkspaceCommand

/**
 * An [AppServerClient] whose every call goes to [target], the client of the connection that is
 * current right now. Facades whose connection changes underneath a stable reference extend it:
 * [ReconnectingAppServerClient] (socket generations) and the Android Iroh transport's dial-switching
 * client (letta-mobile-by4vm.95). [target] throws when no connection may take the call.
 */
abstract class ForwardingAppServerClient : AppServerClient {
    protected abstract fun target(): AppServerClient

    override suspend fun auth(command: AppServerCommand.Auth): AppServerInboundFrame.AuthResponse =
        target().auth(command)

    override suspend fun appServerInfo(command: AppServerCommand.AppServerInfo): AppServerInboundFrame.AppServerInfoResponse =
        target().appServerInfo(command)

    override suspend fun runtimeStart(
        command: AppServerCommand.RuntimeStart,
    ): AppServerInboundFrame.RuntimeStartResponse = target().runtimeStart(command)

    override suspend fun input(command: AppServerCommand.Input) = target().input(command)

    override suspend fun inputAwaitingAcceptance(command: AppServerCommand.Input): AppServerInboundFrame.InputAccepted =
        target().inputAwaitingAcceptance(command)

    override suspend fun changeDeviceState(command: AppServerCommand.ChangeDeviceState) = target().changeDeviceState(command)

    override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
        target().sync(command)

    override suspend fun abort(command: AppServerCommand.AbortMessage): AppServerInboundFrame.AbortMessageResponse =
        target().abort(command)

    override suspend fun resumeQueue(command: AppServerCommand.ResumeQueue): AppServerInboundFrame.ResumeQueueResponse =
        target().resumeQueue(command)

    override suspend fun removeQueueItem(
        command: AppServerCommand.RemoveQueueItem,
    ): AppServerInboundFrame.RemoveQueueItemResponse = target().removeQueueItem(command)

    override suspend fun adminRpc(command: AppServerCommand.AdminRpc): AppServerInboundFrame.AdminRpcResponse =
        target().adminRpc(command)

    override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) =
        target().sendExternalToolResponse(command)

    override suspend fun agentList(command: AppServerCommand.AgentList) = target().agentList(command)

    override suspend fun agentRetrieve(command: AppServerCommand.AgentRetrieve) = target().agentRetrieve(command)

    override suspend fun agentCreate(command: AppServerCommand.AgentCreate) = target().agentCreate(command)

    override suspend fun agentUpdate(command: AppServerCommand.AgentUpdate) = target().agentUpdate(command)

    override suspend fun agentDelete(command: AppServerCommand.AgentDelete) = target().agentDelete(command)

    override suspend fun conversationList(command: AppServerCommand.ConversationList) = target().conversationList(command)

    override suspend fun conversationRetrieve(command: AppServerCommand.ConversationRetrieve) =
        target().conversationRetrieve(command)

    override suspend fun conversationCreate(command: AppServerCommand.ConversationCreate) =
        target().conversationCreate(command)

    override suspend fun conversationUpdate(command: AppServerCommand.ConversationUpdate) =
        target().conversationUpdate(command)

    override suspend fun conversationFork(command: com.letta.mobile.data.transport.appserver.AppServerConversationFork) =
        target().conversationFork(command)

    override suspend fun conversationRecompile(command: com.letta.mobile.data.transport.appserver.AppServerConversationRecompile) =
        target().conversationRecompile(command)

    override suspend fun conversationMessagesList(command: AppServerCommand.ConversationMessagesList) =
        target().conversationMessagesList(command)

    override suspend fun conversationCompact(command: AppServerCommand.ConversationCompact) =
        target().conversationCompact(command)

    override suspend fun listModels(command: AppServerCommand.ListModels) = target().listModels(command)

    override suspend fun skillEnable(command: AppServerCommand.SkillEnable) = target().skillEnable(command)

    override suspend fun listConnectProviders(command: AppServerCommand.ListConnectProviders) =
        target().listConnectProviders(command)

    override suspend fun connectProvider(command: AppServerCommand.ConnectProvider) = target().connectProvider(command)

    override suspend fun disconnectProvider(command: AppServerCommand.DisconnectProvider) =
        target().disconnectProvider(command)

    override suspend fun updateModel(command: AppServerCommand.UpdateModel) = target().updateModel(command)

    override suspend fun skillDisable(command: AppServerCommand.SkillDisable) = target().skillDisable(command)

    // bfooy.5: without these forwarders the interface default throws
    // UnsupportedOperationException, so the wrapper's block.update_agent /
    // block.create_agent / block.delete_agent (routed through DualLane's
    // runtime lane, a ReconnectingAppServerClient) could never reach the socket.
    override suspend fun writeMemoryFile(command: AppServerCommand.WriteMemoryFile) = target().writeMemoryFile(command)

    override suspend fun deleteMemoryFile(command: AppServerCommand.DeleteMemoryFile) = target().deleteMemoryFile(command)

    override suspend fun cronList(command: AppServerCommand.CronList) = target().cronList(command)

    override suspend fun cronAdd(command: AppServerCommand.CronAdd) = target().cronAdd(command)

    override suspend fun cronGet(command: AppServerCommand.CronGet) = target().cronGet(command)

    override suspend fun cronRuns(command: AppServerCommand.CronRuns) = target().cronRuns(command)

    override suspend fun cronTrigger(command: AppServerCommand.CronTrigger) = target().cronTrigger(command)

    override suspend fun cronUpdate(command: AppServerCommand.CronUpdate) = target().cronUpdate(command)

    override suspend fun cronDelete(command: AppServerCommand.CronDelete) = target().cronDelete(command)

    override suspend fun cronDeleteAll(command: AppServerCommand.CronDeleteAll) = target().cronDeleteAll(command)

    // Without these forwarders the interface default throws UnsupportedOperationException, so the
    // Iroh host answered every /context-limit (execute_command) with capability_unavailable and
    // the drawer's context slider vanished (letta-mobile-by4vm.82 follow-up).
    override suspend fun cronPause(command: AppServerCommand.CronPause) = target().cronPause(command)

    override suspend fun cronResume(command: AppServerCommand.CronResume) = target().cronResume(command)

    override suspend fun executeCommand(command: AppServerCommand.ExecuteCommand) = target().executeCommand(command)

    override suspend fun monitorStop(command: AppServerCommand.MonitorStop) = target().monitorStop(command)

    override suspend fun updateToolset(command: AppServerCommand.UpdateToolset) = target().updateToolset(command)

    override suspend fun getCwdMap(command: AppServerCommand.GetCwdMap) = target().getCwdMap(command)

    override suspend fun getReflectionSettings(command: AppServerCommand.GetReflectionSettings) = target().getReflectionSettings(command)

    override suspend fun setReflectionSettings(command: AppServerCommand.SetReflectionSettings) = target().setReflectionSettings(command)

    override suspend fun workspaceRequest(command: AppServerWorkspaceCommand) = target().workspaceRequest(command)

    // lgns8.23: ReconnectingAppServerClient admits these while Recovering so
    // ChannelRestoreCoordinator can run inside onRecovered, on the same generation.
    override suspend fun channelsList(command: AppServerCommand.ChannelsList) = target().channelsList(command)

    override suspend fun channelAccountsList(command: AppServerCommand.ChannelAccountsList) =
        target().channelAccountsList(command)

    override suspend fun channelStart(command: AppServerCommand.ChannelStart) = target().channelStart(command)

    override suspend fun channelAccountUpdate(command: AppServerCommand.ChannelAccountUpdate) =
        target().channelAccountUpdate(command)
}
