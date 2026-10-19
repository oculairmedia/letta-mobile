package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.runtime.PermissionModeRpc
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.util.Telemetry
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Where the host writes `permission_mode` frames: the viewers of one conversation; returns how many accepted it. */
fun interface PermissionModePushTarget {
    suspend fun publish(conversationId: String, frame: String): Int
}

/**
 * Tells every viewer of a conversation that its runtime's permission mode changed
 * on this host, as a `permission_mode` frame ([PermissionModeRpc.FRAME_TYPE]).
 *
 * `permission_mode.set` used to pin the mode and tell no one: a second client (desktop over `iroh://`,
 * or another phone) kept showing the old chip until it reopened the conversation. Only a CONFIRMED
 * change is published ([PermissionModeAdminHandlers]); a client that opens the conversation later
 * reads the mode with `permission_mode.get`, so there is nothing to replay.
 *
 * Each viewer gets the frame once, the sender included: its own result already settled the chip, and
 * folding the same mode again is a no-op ([com.letta.mobile.data.runtime.PermissionModeRegistry.observed]).
 * Sending happens on [scope], so a slow viewer never holds up the `set` reply.
 */
class PermissionModePublisher(
    private val scope: CoroutineScope,
    private val clock: () -> Instant = Instant::now,
) {
    @Volatile private var target: PermissionModePushTarget? = null

    /** [target] writes one frame to the viewers of a conversation (the endpoint's connection registry). */
    fun attach(target: PermissionModePushTarget) {
        this.target = target
    }

    fun publish(runtime: AppServerRuntimeScope, mode: AppServerPermissionMode) {
        val sink = target ?: return
        val frame = PermissionModeRpc.encodeFrame(runtime.agentId, runtime.conversationId, mode, clock().toString()).toString()
        scope.launch {
            val delivered = try {
                sink.publish(runtime.conversationId, frame)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
                Telemetry.event("PermissionModePush", "failed", "class" to error::class.simpleName, level = Telemetry.Level.WARN)
                return@launch
            }
            Telemetry.event("PermissionModePush", "published", "mode" to PermissionModeRpc.wire(mode), "delivered" to delivered)
        }
    }
}
