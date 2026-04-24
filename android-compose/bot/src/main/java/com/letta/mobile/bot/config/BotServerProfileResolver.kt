package com.letta.mobile.bot.config

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BotServerProfileResolver @Inject constructor(
    private val profileStore: IBotServerProfileStore,
) {
    suspend fun resolve(config: BotConfig): ResolvedRemoteProfile? {
        if (config.mode != BotConfig.Mode.REMOTE) return null

        val resolvedProfile = config.serverProfileId?.let { profileStore.findById(it) }
            ?: profileStore.getActiveProfile()

        val baseUrl = resolvedProfile?.baseUrl ?: config.remoteUrl
        if (baseUrl.isNullOrBlank()) return null

        return ResolvedRemoteProfile(
            profileId = resolvedProfile?.id,
            baseUrl = baseUrl,
            authToken = resolvedProfile?.authToken ?: config.remoteToken,
            transport = resolvedProfile?.transport ?: config.transport,
        )
    }
}

data class ResolvedRemoteProfile(
    val profileId: String?,
    val baseUrl: String,
    val authToken: String?,
    val transport: BotConfig.Transport,
)
