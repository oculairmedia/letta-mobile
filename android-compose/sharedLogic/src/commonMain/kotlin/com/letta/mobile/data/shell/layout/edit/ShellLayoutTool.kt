package com.letta.mobile.data.shell.layout.edit

import com.letta.mobile.data.controller.capability.Capability
import com.letta.mobile.data.controller.extras.ExternalToolCaller
import com.letta.mobile.data.controller.extras.ExternalToolResult
import com.letta.mobile.data.controller.extras.HostExternalTool
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.shell.layout.ShellLayoutConfig
import kotlinx.serialization.json.JsonObject

/**
 * The `shell_layout` tool: lets an agent read and change the shell layout of the app it is talking
 * to, through [port] (the shell on screen). A change applies at once and the shell shows the person
 * a notice with Undo; the page never leaves the main lane, Settings never leaves the side menu and
 * Home never leaves the agent rail ([ShellLayoutEdits]). Every answer is a JSON receipt ([ShellLayoutReceipts]); a refusal lists each
 * problem so the agent can correct itself in the same turn. Data only: the ops name catalog views.
 *
 * @param port the shell this tool changes; [ShellLayoutAgentPort.Shared] in the app.
 */
class ShellLayoutTool(private val port: ShellLayoutAgentPort = ShellLayoutAgentPort.Shared) : HostExternalTool {
    override val name: String = NAME

    override val description: String =
        "Change how this app's shell is laid out for the person using it: reorder Home's sections, show or hide parts of " +
            "the agent rail and side menu, or place canvas boards beside the page. A change applies immediately and the " +
            "person sees it at once, with Undo; change the layout only when it serves what they asked for. " +
            "action: get (current lanes and the views each may hold), apply (ops, all or nothing; dry_run: true checks " +
            "without applying), undo (the last change), reset (the default layout). Ops: set_lane {lane, slots}, " +
            "add {lane, view, object?, index?}, remove {lane, view, object?}, move {lane, view, object?, to_lane?, index?}, " +
            "reset. The page stays in the main lane, Settings in the side menu and Home in the agent rail. " +
            "A refusal lists each problem (op_index, rule, detail) and applies nothing."

    override val inputSchema: JsonObject = ShellLayoutSchema.input

    /** Host tools are offered whatever the backend advertises; this value is never used as a gate. */
    override val capability: Capability = Capability.ImageHydration

    override suspend fun invoke(input: JsonObject, agent: String?): ExternalToolResult = invoke(input, ExternalToolCaller(agent))

    override suspend fun invoke(input: JsonObject, caller: ExternalToolCaller): ExternalToolResult {
        val reading = ShellLayoutToolInput.read(input)
        val request = reading.request ?: return ShellLayoutReceipts.refused(null, reading.errors)
        val current = port.attached.value?.config?.value ?: return ShellLayoutReceipts.noShell(request.action)
        val author = ShellLayoutAuthor.Agent(caller.agentId?.let(::AgentId))
        return when (request.action) {
            ShellLayoutAction.GET -> ShellLayoutReceipts.current(current, port.attached.value?.history?.size ?: 0)
            ShellLayoutAction.APPLY -> apply(request, current, author)
            ShellLayoutAction.UNDO -> ShellLayoutReceipts.committed(request.action, port.undoLatest(author))
            ShellLayoutAction.RESET -> ShellLayoutReceipts.committed(request.action, port.reset(author))
        }
    }

    private fun apply(request: ShellLayoutRequest, current: ShellLayoutConfig, author: ShellLayoutAuthor): ExternalToolResult =
        when (val result = ShellLayoutEdits.apply(current, request.ops)) {
            is ShellLayoutEditResult.Rejected -> ShellLayoutReceipts.refused(request.action, result.errors)
            is ShellLayoutEditResult.Applied -> if (request.dryRun || result.changes.isEmpty()) {
                ShellLayoutReceipts.checked(result, dryRun = request.dryRun)
            } else {
                val summary = ShellLayoutReceipts.summary(result.changes)
                ShellLayoutReceipts.committed(request.action, port.commit(author, summary, result.after, current))
            }
        }

    /** The tool's name. */
    companion object {
        /** The name the agent calls the tool by. */
        const val NAME = "shell_layout"
    }
}
