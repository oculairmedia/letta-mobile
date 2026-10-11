package com.letta.mobile.data.context.limit

import com.letta.mobile.data.compaction.CommandAnswer
import com.letta.mobile.data.compaction.executeCommandAnswer
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.util.runCatchingCancellable

/**
 * letta-mobile-joigh: the context limit over a direct App Server client — letta-code's own
 * `execute_command context-limit <tokens>`, so letta-code validates the value and chooses the
 * scope exactly as its TUI does.
 *
 * [supportedCommands] is the server's `device_status.supported_commands`, or null when no status
 * has arrived (then the command is simply tried). A server that lists commands without
 * `context-limit` (letta-code before the command existed, e.g. the embedded 0.26.1 runtime), or
 * answers "Unknown command", or a client without `execute_command`, is
 * [ContextLimitOutcome.Unsupported].
 */
class AppServerContextLimitRepository(
    private val client: suspend () -> AppServerClient?,
    private val supportedCommands: suspend (ContextLimitRequest) -> List<String>? = { null },
    private val requestId: (String) -> String = { "context-limit-$it" },
) : ContextLimitRepository {
    override suspend fun apply(request: ContextLimitRequest): ContextLimitOutcome {
        val appServer = client() ?: return ContextLimitOutcome.Unsupported
        val commands = supportedCommands(request)
        if (commands != null && ContextLimitRpc.COMMAND_ID !in commands) return ContextLimitOutcome.Unsupported
        val command = AppServerCommand.ExecuteCommand(
            requestId = requestId(request.wireConversationId),
            commandId = ContextLimitRpc.COMMAND_ID,
            args = request.tokens.toString(),
            runtime = AppServerRuntimeScope(request.agentId.value, request.wireConversationId),
        )
        val answer = runCatchingCancellable { appServer.executeCommandAnswer(command) }.getOrElse { error ->
            return when (error) {
                is UnsupportedOperationException -> ContextLimitOutcome.Unsupported
                else -> ContextLimitOutcome.Failed(error.message ?: "Couldn't change the context limit")
            }
        }
        return answer.toOutcome(request.tokens)
    }
}

/** letta-mobile-joigh: one `/context-limit` answer, shared by the client path and the host relay. */
fun CommandAnswer.toOutcome(requested: Int): ContextLimitOutcome = when {
    !success && output.orEmpty().startsWith(UNKNOWN_COMMAND) -> ContextLimitOutcome.Unsupported
    // letta-code wraps a thrown error as "Failed: <message>" (its validation text, e.g. the 30,000 floor).
    !success -> ContextLimitOutcome.Failed(output?.removePrefix(FAILED_PREFIX)?.takeIf { it.isNotBlank() } ?: "Couldn't change the context limit")
    else -> ContextLimitOutcome.Applied(ContextLimitCommandOutput.parse(output, requested))
}

private const val UNKNOWN_COMMAND = "Unknown command"
private const val FAILED_PREFIX = "Failed: "
