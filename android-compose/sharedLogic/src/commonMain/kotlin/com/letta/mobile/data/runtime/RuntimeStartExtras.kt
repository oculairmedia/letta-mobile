package com.letta.mobile.data.runtime

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ToolAudience
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeStartClientInfo
import com.letta.mobile.runtime.TurnCommand

/** Values `runtime_start` takes besides the turn's agent and conversation. */
internal data class RuntimeStartExtras(
    val requestId: String,
    val cwd: String?,
    val mode: AppServerPermissionMode,
    val clientInfo: AppServerRuntimeStartClientInfo,
)

/**
 * lgns8.17(a): advertise the same external tools the controller would; an omitted field
 * means the server can never emit `external_tool_call_request`.
 * letta-mobile-k5nbq.9: [RuntimeStartExtras.cwd] is the agent's saved default directory.
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
