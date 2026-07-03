package com.letta.mobile.avatar.rendererweb

import com.letta.mobile.avatar.core.AvatarCapabilities
import com.letta.mobile.avatar.core.AvatarExpression
import com.letta.mobile.avatar.core.AvatarFormat
import com.letta.mobile.avatar.core.AvatarGesture
import com.letta.mobile.avatar.core.AvatarLookTarget
import com.letta.mobile.avatar.core.AvatarModel
import com.letta.mobile.avatar.core.AvatarViseme
import com.letta.mobile.avatar.core.HeadlessAvatarRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Transport seam between [WebAvatarRuntime] and the renderer process/page:
 * a JCEF/WebView JS bridge on desktop/Android, a WebSocket in the dev
 * harness. String-in/string-out so implementations stay trivial.
 */
interface AvatarRendererTransport {
    /** Deliver [message] (a serialized [AvatarRendererCommand]) to the renderer. */
    fun send(message: String)

    /**
     * Install the handler for renderer → host messages (serialized
     * [AvatarRendererEvent]s). Implementations deliver messages sequentially.
     */
    fun setMessageHandler(handler: (String) -> Unit)

    fun close()
}

/**
 * [com.letta.mobile.avatar.core.AvatarRuntime] implementation that drives a
 * remote renderer over [AvatarWireProtocol]. Inherits the full state machine,
 * capability gating, and command bookkeeping from [HeadlessAvatarRuntime] and
 * forwards each post-gate command over the [transport].
 *
 * Loading is ack-based: `load()` suspends until the renderer reports
 * [AvatarRendererEvent.AvatarLoaded] (whose renderer-verified capabilities
 * become the Ready state) or [AvatarRendererEvent.AvatarLoadFailed], bounded
 * by [loadTimeoutMillis].
 *
 * Owns the [transport]: [dispose] closes it.
 */
class WebAvatarRuntime(
    private val transport: AvatarRendererTransport,
    private val loadTimeoutMillis: Long = 30_000,
    private val onRendererError: (String) -> Unit = {},
) : HeadlessAvatarRuntime() {
    private val rendererReady = CompletableDeferred<Int>()
    private var pendingLoad: PendingLoad? = null
    private var loadCounter = 0

    private class PendingLoad(
        val requestId: String,
        val result: CompletableDeferred<AvatarCapabilities> = CompletableDeferred(),
    )

    init {
        transport.setMessageHandler(::handleRendererMessage)
    }

    override suspend fun loadCapabilities(model: AvatarModel): AvatarCapabilities {
        // A replacement load supersedes any still-pending one.
        pendingLoad?.result?.completeExceptionally(
            AvatarWireException("Superseded by a newer load"),
        )

        return try {
            withTimeout(loadTimeoutMillis) {
                val protocolVersion = rendererReady.await()
                if (protocolVersion != AvatarWireProtocol.VERSION) {
                    throw AvatarWireException(
                        "Renderer speaks protocol $protocolVersion; host needs ${AvatarWireProtocol.VERSION}",
                    )
                }

                loadCounter += 1
                val pending = PendingLoad(requestId = "load-$loadCounter")
                pendingLoad = pending
                sendCommand(
                    AvatarRendererCommand.LoadAvatar(
                        url = model.uri,
                        format = model.format.wireName,
                        requestId = pending.requestId,
                    ),
                )
                try {
                    pending.result.await()
                } finally {
                    if (pendingLoad === pending) pendingLoad = null
                }
            }
        } catch (e: TimeoutCancellationException) {
            // OUR timeout is a load failure, not a caller cancellation — it
            // must not ride the CancellationException path back to Idle.
            throw AvatarWireException(
                "Renderer did not acknowledge load within ${loadTimeoutMillis}ms",
                e,
            )
        }
    }

    override fun onUnload() {
        sendCommand(AvatarRendererCommand.Unload)
    }

    override fun onExpressionChanged(expression: AvatarExpression, weight: Float) {
        sendCommand(AvatarRendererCommand.SetExpression(expression.key, weight))
    }

    override fun onVisemeChanged(viseme: AvatarViseme, weight: Float) {
        sendCommand(AvatarRendererCommand.SetViseme(viseme.key, weight))
    }

    override fun onMouthOpenChanged(value: Float) {
        sendCommand(AvatarRendererCommand.SetMouthOpen(value))
    }

    override fun onLookTargetChanged(target: AvatarLookTarget?) {
        sendCommand(
            when (target) {
                null -> AvatarRendererCommand.ClearLookTarget
                is AvatarLookTarget.World ->
                    AvatarRendererCommand.SetLookTarget("world", target.x, target.y, target.z)
                is AvatarLookTarget.Screen ->
                    AvatarRendererCommand.SetLookTarget("screen", target.x, target.y)
            },
        )
    }

    override fun onPlayGesture(gesture: AvatarGesture, fadeSeconds: Float) {
        sendCommand(AvatarRendererCommand.PlayGesture(gesture.id, fadeSeconds))
    }

    override fun onPlayAnimation(animationId: String, loop: Boolean) {
        sendCommand(AvatarRendererCommand.PlayAnimation(animationId, loop))
    }

    override fun onAccessoryToggled(accessoryId: String, enabled: Boolean) {
        sendCommand(AvatarRendererCommand.SetAccessoryEnabled(accessoryId, enabled))
    }

    override fun dispose() {
        super.dispose()
        pendingLoad?.result?.completeExceptionally(AvatarWireException("Runtime disposed"))
        pendingLoad = null
        transport.close()
    }

    private fun handleRendererMessage(message: String) {
        val event = try {
            AvatarWireProtocol.decodeEvent(message)
        } catch (e: AvatarWireException) {
            onRendererError(e.message ?: "Malformed renderer event")
            return
        }
        when (event) {
            is AvatarRendererEvent.Ready -> {
                if (!rendererReady.complete(event.protocolVersion)) {
                    // Renderer rebooted (e.g. page reload) — the current
                    // avatar is gone on the far side; surface it for the host
                    // to decide on a reload.
                    onRendererError(
                        "Renderer re-announced (protocol ${event.protocolVersion}) — a reload may be required",
                    )
                }
            }
            is AvatarRendererEvent.AvatarLoaded -> {
                pendingLoad?.takeIf { it.requestId == event.requestId }
                    ?.result?.complete(event.capabilities)
            }
            is AvatarRendererEvent.AvatarLoadFailed -> {
                pendingLoad?.takeIf { it.requestId == event.requestId }
                    ?.result?.completeExceptionally(AvatarWireException(event.message))
            }
            is AvatarRendererEvent.RendererError -> onRendererError(event.message)
        }
    }

    private fun sendCommand(command: AvatarRendererCommand) {
        // Best-effort: a torn-down transport must not crash command callers.
        try {
            transport.send(AvatarWireProtocol.encodeCommand(command))
        } catch (t: Throwable) {
            onRendererError("Failed to send ${command::class.simpleName}: ${t.message}")
        }
    }

    private val AvatarFormat.wireName: String
        get() = when (this) {
            AvatarFormat.VRM_1 -> "vrm1"
            AvatarFormat.VRM_0 -> "vrm0"
            AvatarFormat.GLB -> "glb"
            AvatarFormat.GLTF -> "gltf"
        }
}
