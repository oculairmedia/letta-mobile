package com.letta.mobile.cli.commands

import com.letta.mobile.data.transport.ChannelTransportState
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.iroh.IrohChannelTransport
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

import kotlin.time.Duration.Companion.milliseconds
/**
 * One real mobile client (an [IrohChannelTransport]) participating in the
 * two-client probe. Wraps connect/subscribe/send/redial and continuously
 * collects the transport's reduced [ServerFrame] events so a passive observer's
 * live ingestion can be asserted at the FULL stack seam.
 */
internal class TwoClientEndpoint(
    val label: String,
    private val irohUrl: String,
    private val token: String?,
    private val agentId: String,
    private val scope: CoroutineScope,
) {
    private var transport: IrohChannelTransport = newTransport()
    private val frames = CopyOnWriteArrayList<ServerFrame>()
    private var collector: Job? = null

    private fun newTransport(): IrohChannelTransport =
        IrohChannelTransport(scope = scope, forcedIrohUrl = irohUrl)

    private fun startCollecting() {
        collector?.cancel()
        collector = scope.launch {
            transport.events.collect { frames.add(it) }
        }
    }

    /** Dials + hydrates conversation [conversationId] via message.list so the
     *  server registers this connection as a viewer (eaczz.3). */
    suspend fun connectAndSubscribe(conversationId: String, timeoutMs: Long) {
        startCollecting()
        transport.connect(irohUrl, token.orEmpty(), "two-client-$label", "letta-mobile-r3i1z")
        awaitConnected(timeoutMs)
        subscribe(conversationId)
    }

    /** Re-hydrate = re-register as viewer. Used to (re)subscribe on a live client. */
    suspend fun subscribe(conversationId: String) {
        runCatching {
            transport.adminRpc(
                method = "message.list",
                path = "/v1/conversations/$conversationId/messages?limit=50&order=desc",
                body = null,
            )
        }
    }

    /**
     * Drops this client's connection and redials against a FRESH transport
     * instance, then only reconnects — it does NOT re-hydrate. Re-subscription
     * must happen automatically on the fresh Ready (deliverable A). Reproduces
     * the device-observed "QUIC timed out, app redialed" scenario.
     */
    suspend fun redial(conversationId: String, timeoutMs: Long) {
        // Prime the transport with the viewed conversation so its
        // re-subscribe-on-reconnect has a target, exactly like a live app that
        // had the conversation open before its connection died. We then tear the
        // whole transport down and stand a new one up: the new transport must
        // NOT auto-know the conversation (fresh instance), so we prime IT via one
        // hydrate, then force a redial WITHIN it and assert re-subscribe fires.
        //
        // To keep the redial faithful to the field bug (same long-lived app, same
        // transport, connection dies + redials underneath), we reuse the SAME
        // transport and drive a reconnect through disconnect()+connect(): the
        // recorded viewed-conversation survives on the transport instance, and the
        // fresh Ready must replay message.list with no explicit subscribe() here.
        transport.disconnect()
        awaitDisconnected(timeoutMs)
        transport.connect(irohUrl, token.orEmpty(), "two-client-$label", "letta-mobile-r3i1z")
        awaitConnected(timeoutMs)
        // NOTE: deliberately NO subscribe() call here — re-registration must be
        // automatic via IrohChannelTransport.reSubscribeViewedConversation().
    }

    fun send(conversationId: String, text: String): String {
        val otid = "2client-$label-${UUID.randomUUID()}"
        transport.send(
            agentId = agentId,
            conversationId = conversationId,
            text = text,
            otid = otid,
            contentParts = null,
            startNewConversation = false,
        )
        return otid
    }

    /** Snapshot of all frames seen so far (thread-safe copy). */
    fun snapshot(): List<ServerFrame> = frames.toList()

    /** Clears the observed-frame buffer so the next round asserts fresh. */
    fun clearFrames() = frames.clear()

    suspend fun close() {
        runCatching { transport.disconnect() }
        collector?.cancel()
    }

    private suspend fun awaitConnected(timeoutMs: Long) {
        val ok = withTimeoutOrNull(timeoutMs.milliseconds) {
            while (transport.state.value !is ChannelTransportState.Connected) delay(25.milliseconds)
            true
        }
        require(ok == true) { "$label failed to reach Connected within ${timeoutMs}ms" }
    }

    private suspend fun awaitDisconnected(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs.milliseconds) {
            while (transport.state.value is ChannelTransportState.Connected) delay(25.milliseconds)
            true
        }
    }
}
