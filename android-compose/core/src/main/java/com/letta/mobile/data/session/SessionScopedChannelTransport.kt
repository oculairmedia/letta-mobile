package com.letta.mobile.data.session

import com.letta.mobile.data.a2ui.A2uiAction
import com.letta.mobile.data.transport.A2uiActionDispatchResult
import com.letta.mobile.data.transport.ChannelTransport
import com.letta.mobile.data.transport.ServerFrame
import com.letta.mobile.data.transport.api.IChannelTransport
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.serialization.json.JsonArray

internal fun defaultSessionScopedChannelTransportScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class SessionScopedChannelTransport internal constructor(
    private val sessionManager: SessionManager,
    private val proxyScope: CoroutineScope,
) : IChannelTransport {
    @Inject
    constructor(sessionManager: SessionManager) : this(
        sessionManager = sessionManager,
        proxyScope = defaultSessionScopedChannelTransportScope(),
    )

    private val _state = MutableStateFlow(sessionManager.current.channelTransport.state.value)
    override val state: StateFlow<ChannelTransport.State> = _state

    override val events: SharedFlow<ServerFrame> = sessionManager.currentGraph
        .flatMapLatest { it.channelTransport.events }
        .shareIn(proxyScope, SharingStarted.Eagerly, replay = 0)

    init {
        sessionManager.currentGraph
            .flatMapLatest { it.channelTransport.state }
            .onEach { _state.value = it }
            .launchIn(proxyScope)
    }

    private val current: IChannelTransport
        get() = sessionManager.current.channelTransport

    override suspend fun connect(baseShimUrl: String, token: String, deviceId: String, clientVersion: String) =
        sessionManager.withCurrentSession { it.channelTransport.connect(baseShimUrl, token, deviceId, clientVersion) }

    override fun send(
        agentId: String,
        conversationId: String,
        text: String,
        otid: String?,
        contentParts: JsonArray?,
    ): Boolean = current.send(agentId, conversationId, text, otid, contentParts)

    override fun cancel(): Boolean = current.cancel()
    override fun bye(): Boolean = current.bye()
    override suspend fun disconnect() = sessionManager.withCurrentSession { it.channelTransport.disconnect() }
    override fun sendA2uiAction(action: A2uiAction): A2uiActionDispatchResult = current.sendA2uiAction(action)

    override suspend fun sendCronList(
        agentId: String?,
        conversationId: String?,
        timeoutMs: Long,
    ): ServerFrame.CronListResponse = sessionManager.withCurrentSession { it.channelTransport.sendCronList(agentId, conversationId, timeoutMs) }

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
    ): ServerFrame.CronAddResponse = sessionManager.withCurrentSession { it.channelTransport.sendCronAdd(
        agentId, name, description, prompt, recurring, cron, every, at, timezone, conversationId, timeoutMs,
    ) }

    override suspend fun sendCronGet(taskId: String, timeoutMs: Long): ServerFrame.CronGetResponse =
        sessionManager.withCurrentSession { it.channelTransport.sendCronGet(taskId, timeoutMs) }

    override suspend fun sendCronDelete(taskId: String, timeoutMs: Long): ServerFrame.CronDeleteResponse =
        sessionManager.withCurrentSession { it.channelTransport.sendCronDelete(taskId, timeoutMs) }

    override suspend fun sendCronDeleteAll(agentId: String, timeoutMs: Long): ServerFrame.CronDeleteAllResponse =
        sessionManager.withCurrentSession { it.channelTransport.sendCronDeleteAll(agentId, timeoutMs) }

    fun close() { proxyScope.cancel() }
}
