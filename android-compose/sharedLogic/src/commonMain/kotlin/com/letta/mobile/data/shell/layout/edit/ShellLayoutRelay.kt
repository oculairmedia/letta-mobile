package com.letta.mobile.data.shell.layout.edit

import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.controller.extras.HostExternalTool
import com.letta.mobile.data.controller.extras.relay.AppToolCallResult
import com.letta.mobile.data.controller.extras.relay.AppToolCallScope
import com.letta.mobile.data.controller.extras.relay.AppToolRelayAnswerer
import com.letta.mobile.data.controller.extras.relay.AppToolRelayHost
import com.letta.mobile.data.controller.extras.relay.AppToolRelayResponder
import com.letta.mobile.util.runCatchingCancellable
import kotlin.time.Duration
import kotlinx.serialization.json.JsonObject

/**
 * `shell_layout` for agents an Iroh host serves: the host offers [ShellLayoutRelayTool] under the
 * tool's own name, description and schema, and the call runs in the app viewing the conversation
 * through [answerer], with the same [ShellLayoutTool] (and so the same receipts and Undo notice) as
 * a local call. [TOOLS] is the allow-list both sides relay.
 */
object ShellLayoutRelay {
    /** The tools relayed between an Iroh host and its apps. */
    val TOOLS: Set<String> = setOf(ShellLayoutTool.NAME)

    /** The host's relay, relaying [TOOLS] only. */
    fun host(timeout: Duration = AppToolRelayHost.DEFAULT_TIMEOUT): AppToolRelayHost = AppToolRelayHost(TOOLS, timeout)

    /** The host tools that stand in for the app's, calling [relay]. */
    fun hostTools(relay: AppToolRelayHost): List<HostExternalTool> = listOf(ShellLayoutRelayTool(relay))

    /** Runs relayed calls with a [ShellLayoutTool] on [port]; any tool off [TOOLS] is refused. */
    fun answerer(port: ShellLayoutAgentPort = ShellLayoutAgentPort.Shared): AppToolRelayAnswerer {
        val tool = ShellLayoutTool(port)
        return AppToolRelayAnswerer { request ->
            if (request.toolName !in TOOLS) {
                AppToolCallResult("This app does not run ${request.toolName.ifEmpty { "that tool" }} for its host.", isError = true)
            } else {
                runCatchingCancellable { tool.invoke(request.input, request.scope.toCaller()).toRelayResult() }
                    .getOrElse { failure -> AppToolCallResult("${tool.name} failed in the app: ${failure.message}", isError = true) }
            }
        }
    }

    /** Makes this process's Iroh connections answer relayed `shell_layout` calls on the shared port. */
    fun install(): AutoCloseable = AppToolRelayResponder.install(answerer())
}

/**
 * The Iroh host's `shell_layout`: the app tool's definition (name, description, schema), applying
 * nothing on the host. It answers with whatever the app viewing the caller's conversation returns
 * through [relay].
 */
class ShellLayoutRelayTool(
    private val relay: AppToolRelayHost,
) : HostExternalTool by ShellLayoutTool(ShellLayoutAgentPort()) {
    override suspend fun invoke(input: JsonObject, agent: String?): ExternalToolResult = invoke(input, ExternalToolCaller(agent))

    override suspend fun invoke(input: JsonObject, caller: ExternalToolCaller): ExternalToolResult =
        relay.call(name, input, AppToolCallScope(caller.agentId, caller.conversationId, caller.toolCallId)).toToolResult()
}

private fun AppToolCallScope.toCaller() = ExternalToolCaller(agentId, conversationId, toolCallId)

private fun ExternalToolResult.toRelayResult(): AppToolCallResult = when (this) {
    is ExternalToolResult.Success -> AppToolCallResult(content)
    is ExternalToolResult.Error -> AppToolCallResult(error, isError = true)
}

private fun AppToolCallResult.toToolResult(): ExternalToolResult =
    if (isError) ExternalToolResult.Error(text) else ExternalToolResult.Success(text)
