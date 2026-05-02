package com.letta.mobile.bot.core

import android.util.Log
import com.letta.mobile.bot.channel.ChannelMessage
import com.letta.mobile.bot.channel.DeliveryResult
import com.letta.mobile.bot.config.BotConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-transport router — Kotlin equivalent of lettabot's LettaGateway.
 *
 * Manages the lifecycle of all active bot **transport** sessions. Each
 * [BotConfig] creates one session keyed on `config.id`. The agent
 * identity travels per-message on [ChannelMessage.targetAgentId] and is
 * multiplexed on the wire by the per-agent session pool inside
 * `RemoteBotSession`'s WS client (see lettabot AgentSessionManager
 * connection pool, w2hx.3).
 *
 * letta-mobile-w2hx.4: this used to key sessions on the bound agent ID.
 * That coupled "which transport am I using" with "which agent am I
 * talking to" — a layer violation. Sessions are now agent-agnostic and
 * the gateway picks one per `targetAgentId → configId` mapping.
 */
@Singleton
class BotGateway @Inject constructor(
    private val sessionFactory: BotSessionFactory,
) {
    /** sessions keyed on `config.id` (NOT agent ID) */
    private val _sessions = MutableStateFlow<Map<String, BotSession>>(emptyMap())
    val sessions: StateFlow<Map<String, BotSession>> = _sessions.asStateFlow()

    private val _status = MutableStateFlow(GatewayStatus.STOPPED)
    val status: StateFlow<GatewayStatus> = _status.asStateFlow()

    private val mutex = Mutex()

    /**
     * Start the gateway with the given configurations.
     * Creates and starts a [BotSession] for each config; sessions are
     * keyed on `config.id`, so multiple configs targeting the same Letta
     * server with different transport settings can coexist.
     */
    suspend fun start(configs: List<BotConfig>) {
        mutex.withLock {
            _status.value = GatewayStatus.STARTING
            val sessions = mutableMapOf<String, BotSession>()
            for (config in configs) {
                try {
                    val session = sessionFactory.create(config)
                    session.start()
                    sessions[config.id] = session
                    Log.i(TAG, "Started session for config ${config.id} (${config.displayName})")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start session for ${config.id}", e)
                }
            }
            _sessions.value = sessions
            _status.value = if (sessions.isNotEmpty()) GatewayStatus.RUNNING else GatewayStatus.ERROR
        }
    }

    /** Stop all sessions and shut down the gateway. */
    suspend fun stop() {
        mutex.withLock {
            _status.value = GatewayStatus.STOPPING
            _sessions.value.values.forEach { session ->
                try {
                    session.stop()
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping session ${session.configId}", e)
                }
            }
            _sessions.value = emptyMap()
            _status.value = GatewayStatus.STOPPED
        }
    }

    /** Get a session by config ID, or null if not running. */
    fun getSession(configId: String): BotSession? = _sessions.value[configId]

    /** Get the default (first) session, or null. */
    fun getDefaultSession(): BotSession? = _sessions.value.values.firstOrNull()

    /**
     * Route an incoming message to a transport session.
     *
     * letta-mobile-w2hx.4: previously dispatched on `message.targetAgentId`
     * (treating agent ID as session key). Now we always pick the default
     * (and currently only) session — the agent identity is forwarded
     * per-message and the WS layer's per-agent pool handles multiplex.
     */
    suspend fun routeMessage(message: ChannelMessage, conversationId: String? = null): BotResponse {
        val session = getDefaultSession()
            ?: throw IllegalStateException("No active bot sessions")

        return session.sendToAgent(message, conversationId)
    }

    fun streamMessage(
        message: ChannelMessage,
        conversationId: String? = null,
    ): Flow<BotResponseChunk> {
        val session = getDefaultSession()
            ?: throw IllegalStateException("No active bot sessions")

        // letta-mobile-w2hx.7: freshness comes from a null conversationId,
        // not from a force_new flag. The chat row's conv_id (or its
        // absence) is the routing key end-to-end.
        return session.streamToAgent(message, conversationId)
    }

    /**
     * Route a message and deliver the response back to the channel.
     */
    suspend fun routeAndDeliver(message: ChannelMessage): DeliveryResult {
        val session = getDefaultSession()
            ?: throw IllegalStateException("No active bot sessions")

        val response = session.sendToAgent(message)
        return session.deliverToChannel(response, message)
    }

    companion object {
        private const val TAG = "BotGateway"
    }
}

enum class GatewayStatus {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR,
}
