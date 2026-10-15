package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.controller.extras.ExternalToolRegistry
import com.letta.mobile.data.controller.fanout.AppServerRuntimeEventRouter
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.runtime.answerApprovalReplaysFrom
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeStartClientInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * letta-mobile-by4vm.95: the one turn engine and event router of an [IrohChannelTransport]. They
 * are built once, over an [IrohDialSwitchingClient] whose generations are the transport's dials,
 * and outlive every dial; [IrohDialer] binds each authenticated dial with [startDial] and its
 * handle's close ends it with [endDial].
 *
 * Ending a dial keeps today's loss semantics (step 1 of letta-mobile-by4vm.54): every turn the dial
 * carried ends, exactly as the per-dial router's detach ended them, and the engine forgets what was
 * local to that connection (its runtime scopes, the dial's inbound control requests), as a fresh
 * engine on the next dial would. letta-mobile-by4vm.96 replaces the turn ending with an
 * interruption the next dial restores.
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

    init {
        router.attach(scope, client.events)
    }

    /** The transport's engine, built on first use; [clientVersion] is the version runtime_start reports. */
    fun engine(clientVersion: String): AppServerTurnEngine =
        engine ?: synchronized(this) { engine ?: buildEngine(clientVersion).also { engine = it } }

    /**
     * Binds [dialClient] as the current dial, after ending one a close has not ended yet (a dial
     * replaced before its close ran). [bindIntegrity] puts the dial's stream-integrity monitor on
     * the router before the dial's first frame is forwarded, so that frame is already observed.
     */
    suspend fun startDial(
        dialClient: AppServerClient,
        bindIntegrity: (AppServerRuntimeEventRouter) -> Job?,
    ): IrohBoundDial = dialLock.withLock {
        current?.let { replaced -> end(replaced) }
        val integrityJob = bindIntegrity(router)
        IrohBoundDial(client.bind(dialClient), integrityJob).also { current = it }
    }

    /** Ends [dial] unless a later dial already replaced (and so ended) it. */
    suspend fun endDial(dial: IrohBoundDial) = dialLock.withLock {
        if (current === dial) end(dial)
    }

    private suspend fun end(dial: IrohBoundDial) {
        current = null
        client.unbind(dial.dial)
        dial.integrityJob?.cancel()
        router.unbindStreamIntegrity()
        router.endSubscribedTurns()
        router.inboundControlRegistry().failGeneration(dial.dial.generation)
        engine?.invalidateRuntime(notifyHost = false)
    }

    private fun buildEngine(clientVersion: String): AppServerTurnEngine = AppServerTurnEngine(
        client = client,
        clientInfo = AppServerRuntimeStartClientInfo(
            name = "letta-mobile-android-iroh",
            version = clientVersion,
        ),
        permissionMode = AppServerPermissionMode.Unrestricted,
        eventRouter = router,
        connectionGenerationProvider = { client.generation },
        externalToolRegistry = externalToolRegistry,
    ).also { it.answerApprovalReplaysFrom(router, scope) }
}

/** One dial bound to an [IrohTransportEngine], with its stream-integrity loop (null when not bound). */
internal class IrohBoundDial(val dial: IrohDial, val integrityJob: Job?)
