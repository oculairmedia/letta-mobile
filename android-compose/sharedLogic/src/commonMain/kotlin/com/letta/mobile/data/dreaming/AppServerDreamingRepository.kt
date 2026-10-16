package com.letta.mobile.data.dreaming

import com.letta.mobile.data.compaction.CommandAnswer
import com.letta.mobile.data.compaction.executeCommandAnswer
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.util.runCatchingCancellable
import kotlinx.serialization.json.JsonObject

/**
 * letta-mobile-iczm7: dreaming over a direct App Server client (the desktop's bundled runtime, an
 * App Server URL): letta-code's own `get/set_reflection_settings` and `execute_command dream`.
 *
 * [supportedCommands] is the server's `device_status.supported_commands`, or null when no status
 * has arrived (then `/dream` is simply tried). A server that lists commands without `dream`
 * (letta-code before 0.33) or answers "Unknown command" cannot dream:
 * [DreamUnsupportedReason.RuntimeTooOld]. A client that does not carry these requests at all
 * (`UnsupportedOperationException`), or no client, is [DreamUnsupportedReason.NoConnection].
 */
class AppServerDreamingRepository(
    private val client: suspend () -> AppServerClient?,
    private val supportedCommands: suspend (DreamTarget) -> List<String>? = { null },
    private val requestId: (String) -> String = { "dream-$it" },
) : DreamingRepository {
    override suspend fun settings(target: DreamTarget): DreamOutcome<DreamSettings> = guarded { appServer ->
        val response = appServer.getReflectionSettings(
            AppServerCommand.GetReflectionSettings(requestId(OP_GET), target.runtime()),
        )
        settingsOutcome(response.success, response.reflectionSettings, response.error)
    }

    override suspend fun save(target: DreamTarget, change: DreamSettingsChange): DreamOutcome<DreamSettings> = guarded { appServer ->
        val response = appServer.setReflectionSettings(
            AppServerCommand.SetReflectionSettings(
                requestId = requestId(OP_SET),
                runtime = target.runtime(),
                settings = change.settings.toWire(),
                scope = change.scope?.wire,
            ),
        )
        settingsOutcome(response.success, response.reflectionSettings, response.error)
    }

    override suspend fun runNow(target: DreamTarget, instruction: String?): DreamOutcome<DreamRunStart> {
        val commands = supportedCommands(target)
        if (commands != null && commands.none { it in DreamingRpc.COMMAND_IDS }) {
            return DreamOutcome.Unsupported(DreamUnsupportedReason.RuntimeTooOld)
        }
        return guarded { appServer ->
            appServer.executeCommandAnswer(
                AppServerCommand.ExecuteCommand(
                    requestId = requestId(OP_RUN),
                    commandId = DreamingRpc.COMMAND_ID,
                    args = DreamingRpc.commandArgs(instruction),
                    runtime = target.runtime(),
                ),
            ).toDreamOutcome()
        }
    }

    private suspend fun <T> guarded(block: suspend (AppServerClient) -> DreamOutcome<T>): DreamOutcome<T> {
        val appServer = client() ?: return DreamOutcome.Unsupported(DreamUnsupportedReason.NoConnection)
        return runCatchingCancellable { block(appServer) }.getOrElse { error ->
            when (error) {
                is UnsupportedOperationException -> DreamOutcome.Unsupported(DreamUnsupportedReason.NoConnection)
                else -> DreamOutcome.Failed(error.message ?: GENERIC_FAILURE)
            }
        }
    }

    private companion object {
        const val OP_GET = "get-reflection-settings"
        const val OP_SET = "set-reflection-settings"
        const val OP_RUN = "dream"
        const val GENERIC_FAILURE = "Couldn't reach the App Server"
    }
}

private fun DreamTarget.runtime() = AppServerRuntimeScope(agentId.value, wireConversationId)

/** A settings answer: the stored settings, letta-code's error, or a blank answer read as a failure. */
internal fun settingsOutcome(success: Boolean, settings: JsonObject?, error: String?): DreamOutcome<DreamSettings> {
    if (!success) return DreamOutcome.Failed(error?.takeIf { it.isNotBlank() } ?: "letta-code refused the dreaming settings")
    return DreamSettings.fromWire(settings)?.let { DreamOutcome.Done(it) }
        ?: DreamOutcome.Failed("letta-code sent no dreaming settings")
}

/** letta-mobile-iczm7: one `/dream` answer, shared by the client path and the host relay. */
fun CommandAnswer.toDreamOutcome(): DreamOutcome<DreamRunStart> = when {
    !success && output.orEmpty().startsWith(UNKNOWN_COMMAND) -> DreamOutcome.Unsupported(DreamUnsupportedReason.RuntimeTooOld)
    else -> DreamOutcome.Done(DreamRunStart.parse(success, output))
}

private const val UNKNOWN_COMMAND = "Unknown command"
