package com.letta.mobile.cli.runtime

import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.data.timeline.headless.HeadlessTimelineStore
import com.letta.mobile.data.transport.ChannelTransport
import com.letta.mobile.data.transport.RunCursorStore
import com.letta.mobile.data.transport.WsChatBridge
import com.letta.mobile.data.transport.WsTimelineEvent
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

internal class CliWsSession(
    private val scope: CoroutineScope,
    private val agentId: String,
    initialConversationId: String,
    private val store: HeadlessTimelineStore = HeadlessTimelineStore(),
    cursorStore: RunCursorStore = RunCursorStore.inMemory(),
    private val printEvents: Boolean = true,
) {
    private val transport = ChannelTransport(cursorStore)
    private val bridge = WsChatBridge(transport)
    private val activeConversationId = AtomicReference(initialConversationId)
    private val activeOtid = AtomicReference<String?>(null)
    private var collector: Job? = null
    private var turnDone = CompletableDeferred<Unit>().apply { complete(Unit) }
    private var lastError: String? = null

    val conversationId: String get() = activeConversationId.get()

    fun startCollecting() {
        if (collector?.isActive == true) return
        collector = scope.launch {
            bridge.events.collect { event -> handleEvent(event) }
        }
    }

    suspend fun connect(
        baseUrl: String,
        token: String,
        deviceId: String,
        clientVersion: String,
        timeoutMs: Long,
    ) {
        bridge.connect(
            baseShimUrl = baseUrl,
            token = token,
            deviceId = deviceId,
            clientVersion = clientVersion,
        )
        withTimeout(timeoutMs) {
            bridge.state.filter { it is ChannelTransport.State.Connected }.first()
        }
        val state = bridge.state.value as ChannelTransport.State.Connected
        println(
            "[ws] connected serverId=${state.serverId} sessionId=${state.sessionId} " +
                "a2ui=${state.a2uiEnabled} canonical=${state.canonicalLiveTransport ?: "<unspecified>"}"
        )
    }

    suspend fun send(
        text: String,
        attachments: List<MessageContentPart.Image>,
        waitForStable: Boolean,
        timeoutMs: Long,
    ): HeadlessTimelineStore {
        if (!turnDone.isCompleted) {
            turnDone.completeExceptionally(IllegalStateException("send called while previous turn in-flight"))
        }
        turnDone = CompletableDeferred()
        lastError = null
        val otid = newCliOtid()
        val sent = bridge.send(
            agentId = agentId,
            conversationId = conversationId,
            text = text,
            otid = otid,
            attachments = attachments,
        )
        if (!sent) {
            store.markExternalTransportLocalFailed(conversationId, otid)
            throw IllegalStateException("ws send was rejected locally")
        }
        activeOtid.set(otid)
        store.appendExternalTransportLocal(
            conversationId = conversationId,
            content = text,
            otid = otid,
            attachments = attachments,
        )
        println("[ws] sent otid=$otid conversationId=$conversationId attachments=${attachments.size}")
        if (waitForStable) {
            try {
                withTimeout(timeoutMs) { turnDone.await() }
            } catch (e: TimeoutCancellationException) {
                throw IllegalStateException("timed out waiting for turn_done after ${timeoutMs}ms", e)
            }
            lastError?.let { throw IllegalStateException(it) }
        }
        return store
    }

    suspend fun dump(pretty: Boolean = true): String = store.dumpJson(conversationId, pretty)

    suspend fun disconnect() {
        bridge.disconnect()
        collector?.cancel()
    }

    private suspend fun handleEvent(event: WsTimelineEvent) {
        when (event) {
            is WsTimelineEvent.TurnStarted -> {
                activeConversationId.set(event.conversationId)
                if (printEvents) {
                    println("[ws] turn_started runId=${event.runId} turnId=${event.turnId}")
                }
            }
            is WsTimelineEvent.MessageDelta -> {
                store.ingestExternalTransportMessage(conversationId, event.message)
                if (printEvents) {
                    println(
                        "[timeline] ${event.message.messageType} id=${event.message.id} " +
                            "runId=${event.message.runId ?: "<none>"} seq=${event.message.seqId ?: "<none>"}"
                    )
                }
            }
            is WsTimelineEvent.StopReason -> {
                if (printEvents) println("[ws] stop_reason=${event.stopReason}")
            }
            is WsTimelineEvent.UsageStatistics -> {
                if (printEvents) {
                    println(
                        "[ws] usage prompt=${event.promptTokens} completion=${event.completionTokens} " +
                            "total=${event.totalTokens}"
                    )
                }
            }
            is WsTimelineEvent.TurnDone -> {
                val otid = activeOtid.getAndSet(null)
                if (otid != null) store.markExternalTransportLocalSent(conversationId, otid)
                store.clearExternalTransportActive(conversationId)
                if (printEvents) {
                    println(
                        "[ws] turn_done status=${event.status} runId=${event.runId} " +
                            "lossy=${event.lossy} dropCount=${event.dropCount}"
                    )
                }
                turnDone.complete(Unit)
            }
            is WsTimelineEvent.Error -> {
                lastError = "ws error ${event.code}: ${event.message}"
                if (printEvents) println("[ws] error code=${event.code} message=${event.message}")
            }
            is WsTimelineEvent.Disconnected -> {
                val otid = activeOtid.getAndSet(null)
                if (otid != null) store.markExternalTransportLocalFailed(conversationId, otid)
                store.clearExternalTransportActive(conversationId)
                if (!turnDone.isCompleted) {
                    turnDone.completeExceptionally(
                        IllegalStateException("ws disconnected code=${event.code} reason=${event.reason}")
                    )
                }
                if (printEvents) println("[ws] disconnected code=${event.code} reason=${event.reason}")
            }
            is WsTimelineEvent.UserActionOutcome -> {
                if (printEvents) println("[ws] user_action outcome=${event.outcome} frameId=${event.frameId}")
            }
        }
    }
}
