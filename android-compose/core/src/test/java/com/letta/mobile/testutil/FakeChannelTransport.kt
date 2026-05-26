package com.letta.mobile.testutil

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.transport.A2uiActionDispatchResult
import com.letta.mobile.data.transport.ChannelTransport
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.IChannelTransport
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonArray

/**
 * Hand-written fake for [IChannelTransport]. It keeps tests away from
 * MockK bytecode instrumentation on the stateful concrete WebSocket
 * transport while still exposing live state/events flows for repository
 * observers.
 */
class FakeChannelTransport(
    initialState: ChannelTransport.State = ChannelTransport.State.Connected(
        serverId = "srv",
        sessionId = "sess",
        deviceId = "dev",
    ),
) : IChannelTransport {
    override val state: MutableStateFlow<ChannelTransport.State> = MutableStateFlow(initialState)
    override val events: MutableSharedFlow<ServerFrame> = MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 16,
    )

    val cronListCalls = mutableListOf<CronListCall>()
    val cronAddCalls = mutableListOf<CronAddCall>()
    val cronGetCalls = mutableListOf<String>()
    val cronDeleteCalls = mutableListOf<String>()
    val cronDeleteAllCalls = mutableListOf<String>()

    var cronListDelayMs: Long = 0L
    var sendResult: Boolean = true
    var cancelResult: Boolean = true
    var byeResult: Boolean = true
    var subscribeResult: Boolean = true
    var a2uiActionResult: A2uiActionDispatchResult = A2uiActionDispatchResult.Sent("fake-action")

    val sendCalls = mutableListOf<SendCall>()
    val subscribeCalls = mutableListOf<SubscribeCall>()

    private val cronListResponses = mutableMapOf<CronListCall, ArrayDeque<ServerFrame.CronListResponse>>()
    private val cronAddResponses = ArrayDeque<ServerFrame.CronAddResponse>()
    private val cronGetResponses = mutableMapOf<String, ArrayDeque<ServerFrame.CronGetResponse>>()
    private val cronDeleteResponses = mutableMapOf<String, ArrayDeque<ServerFrame.CronDeleteResponse>>()
    private val cronDeleteAllResponses = mutableMapOf<String, ArrayDeque<ServerFrame.CronDeleteAllResponse>>()

    fun enqueueCronList(
        agentId: String?,
        conversationId: String? = null,
        vararg responses: ServerFrame.CronListResponse,
    ) {
        cronListResponses.getOrPut(CronListCall(agentId, conversationId)) { ArrayDeque() }
            .addResponses(responses)
    }

    fun enqueueCronAdd(vararg responses: ServerFrame.CronAddResponse) {
        cronAddResponses.addResponses(responses)
    }

    fun enqueueCronGet(taskId: String, vararg responses: ServerFrame.CronGetResponse) {
        cronGetResponses.getOrPut(taskId) { ArrayDeque() }.addResponses(responses)
    }

    fun enqueueCronDelete(taskId: String, vararg responses: ServerFrame.CronDeleteResponse) {
        cronDeleteResponses.getOrPut(taskId) { ArrayDeque() }.addResponses(responses)
    }

    fun enqueueCronDeleteAll(agentId: String, vararg responses: ServerFrame.CronDeleteAllResponse) {
        cronDeleteAllResponses.getOrPut(agentId) { ArrayDeque() }.addResponses(responses)
    }

    override suspend fun connect(
        baseShimUrl: String,
        token: String,
        deviceId: String,
        clientVersion: String,
    ) = Unit

    override fun send(
        agentId: String,
        conversationId: String,
        text: String,
        otid: String?,
        contentParts: JsonArray?,
        startNewConversation: Boolean,
    ): Boolean {
        sendCalls += SendCall(agentId, conversationId, text, otid, contentParts, startNewConversation)
        return sendResult
    }

    override fun cancel(conversationId: String): Boolean = cancelResult

    override fun bye(): Boolean = byeResult

    override suspend fun disconnect() {
        state.value = ChannelTransport.State.Disconnected(1000, "fake disconnect")
    }

    override fun sendA2uiAction(action: A2uiAction): A2uiActionDispatchResult = a2uiActionResult

    override fun subscribe(runId: String, cursor: Long): Boolean {
        subscribeCalls += SubscribeCall(runId, cursor)
        return subscribeResult
    }

    override suspend fun sendCronList(
        agentId: String?,
        conversationId: String?,
        timeoutMs: Long,
    ): ServerFrame.CronListResponse {
        if (cronListDelayMs > 0) delay(cronListDelayMs)
        val call = CronListCall(agentId, conversationId)
        cronListCalls += call
        return cronListResponses[call]?.removeFirstOrNull()
            ?: error("No fake cron_list response queued for $call")
    }

    override suspend fun sendCronAdd(
        agentId: String,
        name: String,
        description: String,
        prompt: String,
        recurring: Boolean,
        cron: String?,
        every: String?,
        at: String?,
        timezone: String?,
        conversationId: String?,
        timeoutMs: Long,
    ): ServerFrame.CronAddResponse {
        cronAddCalls += CronAddCall(
            agentId = agentId,
            name = name,
            description = description,
            prompt = prompt,
            recurring = recurring,
            cron = cron,
            every = every,
            at = at,
            timezone = timezone,
            conversationId = conversationId,
        )
        return cronAddResponses.removeFirstOrNull()
            ?: error("No fake cron_add response queued")
    }

    override suspend fun sendCronGet(
        taskId: String,
        timeoutMs: Long,
    ): ServerFrame.CronGetResponse {
        cronGetCalls += taskId
        return cronGetResponses[taskId]?.removeFirstOrNull()
            ?: error("No fake cron_get response queued for $taskId")
    }

    override suspend fun sendCronDelete(
        taskId: String,
        timeoutMs: Long,
    ): ServerFrame.CronDeleteResponse {
        cronDeleteCalls += taskId
        return cronDeleteResponses[taskId]?.removeFirstOrNull()
            ?: error("No fake cron_delete response queued for $taskId")
    }

    override suspend fun sendCronDeleteAll(
        agentId: String,
        timeoutMs: Long,
    ): ServerFrame.CronDeleteAllResponse {
        cronDeleteAllCalls += agentId
        return cronDeleteAllResponses[agentId]?.removeFirstOrNull()
            ?: error("No fake cron_delete_all response queued for $agentId")
    }

    data class SubscribeCall(
        val runId: String,
        val cursor: Long,
    )

    data class SendCall(
        val agentId: String,
        val conversationId: String,
        val text: String,
        val otid: String?,
        val contentParts: JsonArray?,
        val startNewConversation: Boolean,
    )

    data class CronListCall(
        val agentId: String?,
        val conversationId: String?,
    )

    data class CronAddCall(
        val agentId: String,
        val name: String,
        val description: String,
        val prompt: String,
        val recurring: Boolean,
        val cron: String?,
        val every: String?,
        val at: String?,
        val timezone: String?,
        val conversationId: String?,
    )

    private fun <T> ArrayDeque<T>.addResponses(responses: Array<out T>) {
        responses.forEach(::addLast)
    }
}
