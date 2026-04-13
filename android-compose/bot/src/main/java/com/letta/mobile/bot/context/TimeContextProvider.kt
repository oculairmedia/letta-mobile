package com.letta.mobile.bot.context

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Provides current date/time and timezone as device context.
 * No permissions required.
 */
@Singleton
class TimeContextProvider @Inject constructor() : DeviceContextProvider {

    override val providerId: String = "time"
    override val displayName: String = "Date & Time"
    override val requiredPermissions: List<String> = emptyList()

    override suspend fun hasPermission(): Boolean = true

    override suspend fun gatherContext(): String {
        val now = LocalDateTime.now()
        val zone = ZoneId.systemDefault()
        val formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a", Locale.getDefault())
        return "Current time: ${now.format(formatter)} (${zone.id})"
    }
}
