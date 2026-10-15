package com.letta.mobile.data.transport.iroh

import com.letta.mobile.data.controller.reconnect.AppServerNotConnectedException
import com.letta.mobile.data.controller.reconnect.ForwardingAppServerClient
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * letta-mobile-by4vm.95: the one [AppServerClient] an [IrohChannelTransport]'s turn engine and
 * event router hold across Iroh dials.
 *
 * Calls go to the bound dial's client. While no dial is bound they fail at once with
 * [AppServerNotConnectedException], as [com.letta.mobile.data.controller.reconnect.ReconnectingAppServerClient]
 * does between generations: nothing queues for a later dial.
 *
 * Every dial's frames reach [events] strictly one dial after another: a frame is forwarded only
 * while its dial is the bound one, under the same lock that binds and unbinds, so no frame of an
 * ended dial lands after the next dial's first. Each forwarded frame is stamped with its dial's
 * [IrohDial.generation], so a control request the host replays on the next dial is a new request
 * there, not a duplicate of the one this client already answered on the last.
 */
internal class IrohDialSwitchingClient(private val scope: CoroutineScope) : ForwardingAppServerClient() {
    private val _events = MutableSharedFlow<AppServerReceivedFrame>(extraBufferCapacity = EVENT_BUFFER)
    override val events: Flow<AppServerReceivedFrame> = _events.asSharedFlow()

    private val switchLock = Mutex()
    private val generationSeq = atomic(0L)

    @Volatile
    private var bound: IrohDial? = null

    /** The generation of the bound dial, or of the last one bound; 0 before the first. */
    val generation: Long get() = generationSeq.value

    /**
     * Makes [client] the bound dial, replacing any other. Its frames are collected from the moment
     * this returns (the collection subscribes before it does), as the router's attach did per dial.
     */
    suspend fun bind(client: AppServerClient): IrohDial = switchLock.withLock {
        bound?.pipe?.cancel()
        val dial = IrohDial(client, generationSeq.incrementAndGet())
        bound = dial
        dial.pipe = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { frame -> forward(dial, frame) }
        }
        dial
    }

    /** Stops [dial]'s frames and, if it is still the bound dial, leaves no dial bound. */
    suspend fun unbind(dial: IrohDial) = switchLock.withLock {
        dial.pipe?.cancel()
        if (bound === dial) bound = null
    }

    private suspend fun forward(dial: IrohDial, frame: AppServerReceivedFrame) = switchLock.withLock {
        if (bound === dial) _events.emit(frame.copy(connectionGeneration = dial.generation))
    }

    override fun target(): AppServerClient =
        bound?.client ?: throw AppServerNotConnectedException(
            "Iroh connection is not ready; callers must not queue writes for a later dial",
        )

    private companion object {
        const val EVENT_BUFFER = 256
    }
}

/** One dial bound to an [IrohDialSwitchingClient]: its client, its generation, and its frame pipe. */
internal class IrohDial(val client: AppServerClient, val generation: Long) {
    @Volatile
    internal var pipe: Job? = null
}
