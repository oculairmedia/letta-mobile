package com.letta.mobile.data.plugin.host

import com.letta.mobile.data.canvas.HostCanvasAccess
import com.letta.mobile.data.canvas.HostCanvasBackend
import com.letta.mobile.data.canvas.HostCanvasCaller
import com.letta.mobile.data.canvas.HostCanvasEntry
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

/**
 * The board a plugin's emits land on: the conversation canvas of the agent whose tool call last
 * reached the plugin, or the canvas an emit names when that caller may open it. A plugin nobody
 * has called yet has no board. v1 binds to the LAST caller: a plugin serving two conversations at
 * once places on the later one's board (the action result's own `emit` is applied right after
 * its call, so it lands correctly).
 */
class CallerBoard(private val backend: HostCanvasBackend) : PluginCanvasAccess {
    @Volatile var caller: HostCanvasCaller? = null

    override suspend fun boardFor(requestedCanvasId: String?): HostCanvasEntry? {
        val who = caller ?: return null
        val access = if (requestedCanvasId != null) backend.open(who, requestedCanvasId) else backend.ownConversation(who)
        return (access as? HostCanvasAccess.Granted)?.entry
    }
}

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
        plugin.board.caller = HostCanvasCaller(agentId, caller.conversationId, caller.toolCallId)
        val call = ActionCall(definition.action, input, ActionContext(ActionOrigin.Agent(agentId, caller.conversationId, caller.toolCallId)))
        return try {
            answer(plugin, plugin.driver.invoke(call))
        } catch (e: LcpCallException) {
            ExternalToolResult.Error("${definition.name} failed: ${e.message}")
        }
    }

    private suspend fun answer(plugin: LivePlugin, result: ActionResult): ExternalToolResult = when (result) {
        is ActionResult.Error -> ExternalToolResult.Error("${result.code}: ${result.message}")
        is ActionResult.Ok -> ExternalToolResult.Success(content(result, result.emit?.let { plugin.handlers.emit(it) }))
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
