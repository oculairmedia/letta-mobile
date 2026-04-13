package com.letta.mobile.bot.context

/**
 * Interface for providing device context to the bot's message envelope.
 *
 * In LOCAL mode, the bot can access device APIs (contacts, calendar, location,
 * battery, connectivity, etc.) and inject this context into the system-reminder
 * envelope that wraps each user message before sending to the Letta agent.
 *
 * This is the key differentiator of running the bot on-device vs. remotely:
 * the local bot has direct access to Android APIs and can provide rich
 * device context that a remote server cannot.
 */
interface DeviceContextProvider {

    /** Unique identifier for this provider (e.g., "battery", "location"). */
    val providerId: String

    /** Human-readable label for settings UI. */
    val displayName: String

    /**
     * Gather current device context as a string block.
     * This will be injected into the message envelope's `<system-reminder>` tag.
     *
     * @return Context string, or null if unavailable.
     */
    suspend fun gatherContext(): String?

    /** Whether the required permissions are granted. */
    suspend fun hasPermission(): Boolean

    /** The Android permissions this provider needs. */
    val requiredPermissions: List<String>
}
