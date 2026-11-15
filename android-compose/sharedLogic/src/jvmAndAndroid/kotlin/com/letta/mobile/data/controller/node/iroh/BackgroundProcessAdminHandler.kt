package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Forward process control to the runtime that owns the process; acceptance is not a status update. */
internal fun registerMonitorStop(router: AdminRpcRouter, nativeClient: AppServerClient?) {
    router.registerScoped("monitor.stop") { params, context ->
        val agentId = params.requireParam(AdminParamKey("agent_id"))
        val conversationId = params.requireParam(AdminParamKey("conversation_id"))
        val processId = params.requireParam(AdminParamKey("process_id"))
        if (!context.canAccessConversation(conversationId)) adminError("authz.denied: conversation is out of scope")
        val client = nativeClient ?: adminError("capability_unavailable: monitor operations require the native App Server client")
        val response = client.monitorStop(
            AppServerCommand.MonitorStop(
                requestId = NativeAdmin.requestId(),
                processId = processId,
                runtime = AppServerRuntimeScope(agentId, conversationId),
            ),
        )
        if (!response.success) adminError(response.error ?: "monitor_stop failed")
        buildJsonObject { put("accepted", true) }
    }
}
