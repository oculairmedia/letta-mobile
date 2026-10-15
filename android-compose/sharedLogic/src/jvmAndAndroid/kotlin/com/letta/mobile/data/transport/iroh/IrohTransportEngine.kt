package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.extras.ToolAudience
import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.controller.fanout.RuntimeSessionLink
import com.letta.mobile.data.controller.reconnect.reattachRuntimes
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.runtime.answerApprovalReplaysFrom
import com.letta.mobile.data.runtime.carryActiveLeasesInto
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.data.transport.appserver.AppServerRuntimeStartClientInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * letta-mobile-by4vm.95: the one turn engine and event router of an [IrohChannelTransport]. They
 * are built once, over an [IrohDialSwitchingClient] whose generations are the transport's dials,
 * and outlive every dial; [IrohDialer] binds each authenticated dial with [startDial] and its
 * handle's close ends it with [endDial].
 *
 * letta-mobile-by4vm.96: ending a dial interrupts the session instead of ending its turns, as the
 * desktop's ws:// and iroh:// gateways and the wrapper do (by4vm.9, by4vm.55). Each held lease is
 * carried into the next dial's generation and the router is marked interrupted, so a turn keeps
 * its lease and subscription and waits out
 * [com.letta.mobile.data.runtime.SESSION_RECONNECT_GRACE_MS]. The next dial re-attaches every
 * watched runtime and restores the session ([resumeSession]); only a session that ends for good
 * (config change, disconnect, auth failure) ends its turns ([endTurns]).
 */
internal class IrohTransportEngine(
    private val scope: CoroutineScope,
    private val externalToolRegistry: ExternalToolRegistry?,
) {
    val client = IrohDialSwitchingClient(scope)
    val router = AppServerRuntimeEventRouter(connectionGenerationProvider = { client.generation })

    private val dialLock = Mutex()
    private var current: IrohBoundDial? = null

    @Volatile
    private var engine: AppServerTurnEngine? = null

    @Volatile
    private var clientInfo: AppServerRuntimeStartClientInfo? = null

    init {
        router.attach(scope, client.events)
    }

    /** The transport's engine, built on first use; [clientVersion] is the version runtime_start reports. */
    fun engine(clientVersion: String): AppServerTurnEngine = engine ?: synchronized(this) {
        engine ?: buildEngine(clientInfo(clientVersion)).also { engine = it }
    }

    /**
     * Binds [dialClient] as the current dial, after ending one a close has not ended yet (a dial
     * replaced before its close ran). [bindIntegrity] puts the dial's stream-integrity monitor on
     * the router before the dial's first frame is forwarded, so that frame is already observed.
     */
    suspend fun startDial(
        dialClient: AppServerClient,
        bindIntegrity: (AppServerRuntimeEventRouter) -> Job?,
    ): IrohBoundDial = dialLock.withLock {
        current?.let { replaced -> end(replaced, reason = "dial_replaced") }
        val integrityJob = bindIntegrity(router)
        IrohBoundDial(client.bind(dialClient), integrityJob).also { current = it }
    }

    /**
     * After a dial is bound: when the session was interrupted, re-attaches every runtime still
     * being watched on that dial (runtime_start also registers it as the host's viewer of the
     * conversation, and replays pending approvals), then restores the session, which resyncs them
     * when the host orders event_seq. A refused runtime_start throws, failing the dial; the
     * session stays interrupted for the next one.
     */
    suspend fun resumeSession() {
        if (router.sessionLink.value !is RuntimeSessionLink.Interrupted) return
        // No engine yet means no turn to carry: the session only needs restoring.
        clientInfo?.let { info -> client.reattachRuntimes(router.watchedRuntimes()) { reattachCommand(it, info) } }
        router.restoreSession()
    }

    /** Ends [dial] unless a later dial already replaced (and so ended) it. */
    suspend fun endDial(dial: IrohBoundDial, reason: String) = dialLock.withLock {
        if (current === dial) end(dial, reason)
    }

    /**
     * The session ended for good: every turn ends now, with one connection-lost terminal each. The
     * router stays attached for a later connect.
     */
    fun endTurns() {
        router.endSubscribedTurns()
    }

    private suspend fun end(dial: IrohBoundDial, reason: String) {
        current = null
        // Carried BEFORE the next bind advances the generation, so no frame of the next dial sees
        // a surviving lease as superseded; frames the dead dial stamped still fail the stamp check.
        engine?.carryActiveLeasesInto(client.generation + 1)
        client.unbind(dial.dial)
        dial.integrityJob?.cancel()
        router.unbindStreamIntegrity()
        router.interruptSession(reason)
        // The dial's pending control requests die with it; resumeSession's recoverApprovals
        // replays them on the next dial.
        router.inboundControlRegistry().failGeneration(dial.dial.generation)
        engine?.invalidateRuntime(notifyHost = false)
    }

    private fun reattachCommand(
        runtime: AppServerRuntimeScope,
        clientInfo: AppServerRuntimeStartClientInfo,
    ) = AppServerCommand.RuntimeStart(
        requestId = "iroh-reattach-${UUID.randomUUID()}",
        agentId = runtime.agentId,
        conversationId = runtime.conversationId,
        mode = PERMISSION_MODE,
        clientInfo = clientInfo,
        recoverApprovals = true,
        forceDeviceStatus = true,
        externalTools = externalToolRegistry?.advertisedToolsCommandGroups(audience = ToolAudience(runtime.agentId)),
    )

    private fun buildEngine(info: AppServerRuntimeStartClientInfo): AppServerTurnEngine = AppServerTurnEngine(
        client = client,
        clientInfo = info.also { clientInfo = it },
        permissionMode = PERMISSION_MODE,
        eventRouter = router,
        connectionGenerationProvider = { client.generation },
        externalToolRegistry = externalToolRegistry,
    ).also { it.answerApprovalReplaysFrom(router, scope) }

    private companion object {
        val PERMISSION_MODE = AppServerPermissionMode.Unrestricted

        fun clientInfo(clientVersion: String) = AppServerRuntimeStartClientInfo(
            name = "letta-mobile-android-iroh",
            version = clientVersion,
        )
    }
}

/** One dial bound to an [IrohTransportEngine], with its stream-integrity loop (null when not bound). */
internal class IrohBoundDial(val dial: IrohDial, val integrityJob: Job?)
