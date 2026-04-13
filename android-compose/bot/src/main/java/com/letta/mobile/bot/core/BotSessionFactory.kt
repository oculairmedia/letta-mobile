package com.letta.mobile.bot.core

import com.letta.mobile.bot.config.BotConfig
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Factory for creating [BotSession] instances based on configuration.
 * Decides whether to create a [LocalBotSession] or [RemoteBotSession]
 * based on [BotConfig.mode].
 */
@Singleton
class BotSessionFactory @Inject constructor(
    private val localSessionFactory: LocalBotSession.Factory,
    private val remoteSessionFactory: RemoteBotSession.Factory,
) {
    fun create(config: BotConfig): BotSession = when (config.mode) {
        BotConfig.Mode.LOCAL -> localSessionFactory.create(config)
        BotConfig.Mode.REMOTE -> remoteSessionFactory.create(config)
    }
}
