package com.letta.mobile.data.runtime

import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerDeviceStatePayload
import com.letta.mobile.data.transport.appserver.AppServerDeviceStatusSnapshot
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.appserver.snapshot
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds

/**
 * letta-mobile-qygvv.7: changes a runtime's working directory or permission mode
 * with `change_device_state` — the protocol's own mechanism — instead of
 * re-issuing `runtime_start` (which replays full state and re-registers external
 * tools, racing live turns).
 *
 * `change_device_state` has no direct response; the effect arrives as an
 * `update_device_status` for the same runtime scope. [change] subscribes BEFORE
 * sending, then waits (bounded by [timeoutMs]) for a snapshot of that scope that
 * satisfies the confirmation predicate. A timeout, a send failure, or a server
 * that never confirms returns false. There is deliberately no `runtime_start`
 * fallback here: attach/reattach stays the only caller of that command.
 */
internal class DeviceStateChanger(
    private val client: AppServerClient,
    private val inboundSource: TurnInboundSource,
    private val timeoutMs: Long = DEFAULT_DEVICE_STATE_TIMEOUT_MS,
) {
    /**
     * Confirmed when the scope's `current_working_directory` equals [cwd]. The
     * server answers a rejected (missing) directory with a snapshot that still
     * carries the OLD cwd, and other failures with only a loop error notice, so
     * neither confirms and both end in the bounded timeout.
     */
    suspend fun changeWorkingDirectory(scope: AppServerRuntimeScope, cwd: String): Boolean {
        val requested = normalizeWorkingDirectory(cwd)
        return change(scope, AppServerDeviceStatePayload(cwd = cwd), FIELD_CWD) { status ->
            status.currentWorkingDirectory?.let(::normalizeWorkingDirectory) == requested
        }
    }

    /** Confirmed when the scope's `current_permission_mode` equals [mode]. */
    suspend fun changePermissionMode(scope: AppServerRuntimeScope, mode: AppServerPermissionMode): Boolean =
        change(scope, AppServerDeviceStatePayload(mode = mode), FIELD_MODE) { status ->
            status.currentPermissionMode == mode
        }

    private suspend fun change(
        scope: AppServerRuntimeScope,
        payload: AppServerDeviceStatePayload,
        field: String,
        confirms: (AppServerDeviceStatusSnapshot) -> Boolean,
    ): Boolean {
        val (subscriberId, inbound) = inboundSource.subscribe(scope)
        return try {
            coroutineScope {
                // UNDISPATCHED: the collector subscribes before the command leaves, so
                // a fast update_device_status cannot slip past a zero-replay flow.
                val confirmation = async(start = CoroutineStart.UNDISPATCHED) {
                    inbound
                        .mapNotNull { received -> received.deviceStatusFor(scope) }
                        .first(confirms)
                }
                try {
                    client.changeDeviceState(AppServerCommand.ChangeDeviceState(scope, payload))
                    withTimeout(timeoutMs.milliseconds) { confirmation.await() }
                    true
                } finally {
                    confirmation.cancel()
                }
            }
        } catch (t: TimeoutCancellationException) {
            report(scope, field, "deviceState.changeTimedOut", t)
            false
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            report(scope, field, "deviceState.changeFailed", t)
            false
        } finally {
            inboundSource.unsubscribe(subscriberId)
        }
    }

    private fun report(scope: AppServerRuntimeScope, field: String, event: String, cause: Throwable) {
        Telemetry.event(
            "DeviceStateChanger", event,
            "field" to field,
            "agentId" to scope.agentId,
            "conversationId" to scope.conversationId,
            "timeoutMs" to timeoutMs,
            "error" to (cause.message ?: cause::class.simpleName ?: "<unknown>"),
            level = Telemetry.Level.WARN,
        )
    }

    companion object {
        const val DEFAULT_DEVICE_STATE_TIMEOUT_MS = 10_000L
        private const val FIELD_CWD = "cwd"
        private const val FIELD_MODE = "mode"
    }
}

private fun AppServerReceivedFrame.deviceStatusFor(
    scope: AppServerRuntimeScope,
): AppServerDeviceStatusSnapshot? {
    val status = frame as? AppServerInboundFrame.UpdateDeviceStatus ?: return null
    val runtime = status.runtime
    if (runtime.agentId != scope.agentId || runtime.conversationId != scope.conversationId) return null
    return status.snapshot
}

/** Compares directories the way the server reports them: trimmed, no trailing separator. */
internal fun normalizeWorkingDirectory(path: String): String {
    val trimmed = path.trim()
    return trimmed.trimEnd('/', '\\').ifEmpty { trimmed.take(1) }
}
