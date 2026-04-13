package com.letta.mobile.bot.core

import android.util.Log
import com.letta.mobile.bot.channel.ChannelMessage
import com.letta.mobile.bot.channel.DeliveryResult
import com.letta.mobile.bot.config.BotConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-agent router — Kotlin equivalent of lettabot's LettaGateway.
 *
 * Routes incoming messages to the correct [BotSession] based on agent mapping.
 * Manages the lifecycle of all active bot sessions and provides a unified
 * entry point for channel adapters to deliver messages.
 */
@Singleton
class BotGateway @Inject constructor(
    private val sessionFactory: BotSessionFactory,
) {
    private val _sessions = MutableStateFlow<Map<String, BotSession>>(emptyMap())
    val sessions: StateFlow<Map<String, BotSession>> = _sessions.asStateFlow()

    private val _status = MutableStateFlow(GatewayStatus.STOPPED)
    val status: StateFlow<GatewayStatus> = _status.asStateFlow()

    private val mutex = Mutex()

    /**
     * Start the gateway with the given configurations.
     * Creates and starts a [BotSession] for each agent config.
     */
    suspend fun start(configs: List<BotConfig>) {
        mutex.withLock {
            _status.value = GatewayStatus.STARTING
            val sessions = mutableMapOf<String, BotSession>()
            for (config in configs) {
                try {
                    val session = sessionFactory.create(config)
                    session.start()
                    sessions[config.agentId] = session
                    Log.i(TAG, "Started session for agent ${config.agentId} (${config.displayName})")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start session for ${config.agentId}", e)
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
                    Log.w(TAG, "Error stopping session ${session.agentId}", e)
                }
            }
            _sessions.value = emptyMap()
            _status.value = GatewayStatus.STOPPED
        }
    }

    /** Get a session by agent ID, or null if not running. */
    fun getSession(agentId: String): BotSession? = _sessions.value[agentId]

    /** Get the default (first) session, or null. */
    fun getDefaultSession(): BotSession? = _sessions.value.values.firstOrNull()

    /**
     * Route an incoming message to the appropriate agent.
     * Uses the message's agentId if present, otherwise falls back to default.
     */
    suspend fun routeMessage(message: ChannelMessage): BotResponse {
        val session = message.targetAgentId?.let { getSession(it) }
            ?: getDefaultSession()
            ?: throw IllegalStateException("No active bot sessions")

        return session.sendToAgent(message)
    }

    /**
     * Route a message and deliver the response back to the channel.
     */
    suspend fun routeAndDeliver(message: ChannelMessage): DeliveryResult {
        val session = message.targetAgentId?.let { getSession(it) }
            ?: getDefaultSession()
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
