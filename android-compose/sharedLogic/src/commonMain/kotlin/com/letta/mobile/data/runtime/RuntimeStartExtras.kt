package com.letta.mobile.data.runtime

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ToolAudience
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeStartClientInfo
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.util.runCatchingCancellable

/** Values `runtime_start` takes besides the turn's agent and conversation. */
internal data class RuntimeStartExtras(
    val requestId: String,
    val cwd: String?,
    val mode: AppServerPermissionMode,
    val clientInfo: AppServerRuntimeStartClientInfo,
)

/**
 * (a): advertise the same external tools the controller would; an omitted field
 * means the server can never emit `external_tool_call_request`.
 * [RuntimeStartExtras.cwd] is the agent's saved default directory.
 */
internal fun TurnCommand.runtimeStart(
    extras: RuntimeStartExtras,
    tools: ExternalToolRegistry?,
): AppServerCommand.RuntimeStart = AppServerCommand.RuntimeStart(
    requestId = extras.requestId,
    agentId = agentId.value,
    conversationId = conversationId.value,
    cwd = extras.cwd,
    mode = extras.mode,
    clientInfo = extras.clientInfo,
    recoverApprovals = true,
    forceDeviceStatus = true,
    externalTools = tools?.advertisedToolsCommandGroups(audience = ToolAudience(agentId.value)),
)

/**
 * The `runtime_start.cwd` for [command] — its agent's saved default, only
 * while the runtime keeps no working directory for the conversation. letta-code switches a
 * conversation to any cwd `runtime_start` carries, and persists every chosen one in its cwd map
 * (`get_cwd_map`; a conversation left in the boot directory has no entry). So a conversation whose
 * folder was picked keeps it when its runtime starts again (a restart, a reconnect), and when the
 * map cannot be read no cwd is sent: the runtime keeps whatever it has.
 */
internal suspend fun AgentDefaultsRepository.newConversationCwd(
    command: TurnCommand,
    client: AppServerClient,
    requestIdFactory: () -> String,
): String? {
    val cwd = cwd(command.agentId) ?: return null
    val map = runCatchingCancellable { client.getCwdMap(AppServerCommand.GetCwdMap(requestIdFactory())) }
        .getOrNull()
        ?.takeIf { it.success }
        ?: return null
    val scope = WorkingDirectoryScopeKey.of(command.agentId.value, command.conversationId.value)
    return cwd.takeUnless { scope in map.cwdMap }
}
