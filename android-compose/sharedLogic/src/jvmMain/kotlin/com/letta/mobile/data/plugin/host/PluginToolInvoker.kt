package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.canvas.HostCanvasCaller
import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.plugin.PluginActionInvoker
import com.letta.mobile.data.plugin.PluginToolDefinition
import com.letta.mobile.data.plugin.wire.LcpCallException
import com.letta.mobile.data.plugin.wire.LcpHostHandlers
import com.letta.mobile.data.plugin.wire.LcpWire
import com.letta.mobile.plugin.api.ActionCall
import com.letta.mobile.plugin.api.ActionContext
import com.letta.mobile.plugin.api.ActionOrigin
import com.letta.mobile.plugin.api.ActionResult
import com.letta.mobile.plugin.api.EmitReceipt
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One running plugin: its driver, the handlers its calls reach, and the board those place on. */
class LivePlugin(val driver: PluginDriver, val handlers: LcpHostHandlers, val board: CallerBoard)

/**
 * Agent tool calls to plugin actions (plan section 6.3): the call goes to the plugin's driver with
 * an agent origin, an `emit` in the answer is applied through the plugin's own handlers (rate
 * limits, placement, ownership), and failures come back as tool errors, never exceptions.
 */
class PluginToolInvoker(private val live: (String) -> LivePlugin?) : PluginActionInvoker {
    override suspend fun invoke(definition: PluginToolDefinition, input: kotlinx.serialization.json.JsonObject, caller: ExternalToolCaller): ExternalToolResult {
        val plugin = live(definition.pluginId) ?: return ExternalToolResult.Error("${definition.pluginId} is not running; enable it on the host")
        val agentId = caller.agentId ?: UNKNOWN_AGENT
        val callId = plugin.board.begin(HostCanvasCaller(agentId, caller.conversationId, caller.toolCallId))
        val origin = ActionOrigin.Agent(agentId, caller.conversationId, caller.toolCallId)
        val call = ActionCall(definition.action, input, ActionContext(origin, callId = callId.value))
        return try {
            answer(plugin, plugin.driver.invoke(call), callId)
        } catch (e: LcpCallException) {
            ExternalToolResult.Error("${definition.name} failed: ${e.message}")
        } finally {
            plugin.board.end(callId)
        }
    }

    /** The answer for the agent; an `emit` in it is applied for this very call, whatever call it names. */
    private suspend fun answer(plugin: LivePlugin, result: ActionResult, callId: PluginCallId): ExternalToolResult = when (result) {
        is ActionResult.Error -> ExternalToolResult.Error("${result.code}: ${result.message}")
        is ActionResult.Ok -> ExternalToolResult.Success(content(result, result.emit?.let { plugin.handlers.emit(it.copy(callId = callId.value)) }))
    }

    private fun content(result: ActionResult.Ok, receipt: EmitReceipt?): String {
        if (result.structured == null && receipt == null) return result.text
        return buildJsonObject {
            put("text", result.text)
            result.structured?.let { put("structured", it) }
            receipt?.let { put("emit", LcpWire.json.encodeToJsonElement(EmitReceipt.serializer(), it)) }
        }.toString()
    }

    private companion object {
        /** The agent id of a call the App Server sent without a runtime scope. */
        const val UNKNOWN_AGENT = "unknown-agent"
    }
}
