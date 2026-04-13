package com.letta.mobile.bot.message

import com.letta.mobile.bot.channel.ChannelMessage
import com.letta.mobile.bot.context.DeviceContextProvider
import com.letta.mobile.bot.runtime.memory.RuntimeMemorySnapshot
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Formats incoming channel messages into enriched envelopes before sending to the agent.
 * Kotlin equivalent of lettabot's Formatter class.
 *
 * The envelope wraps the user's raw text with `<system-reminder>` XML tags containing
 * metadata about the channel, sender, device context, and timestamps. This gives the
 * Letta agent rich contextual awareness without modifying its system prompt.
 *
 * Example output:
 * ```
 * <system-reminder>
 * Channel: in_app | Sender: user123 (John)
 * Current time: Saturday, April 12, 2026 at 3:45 PM (America/New_York)
 * Battery: 72% (discharging)
 * Network: connected (WiFi, unmetered)
 * </system-reminder>
 * Hello, how are you?
 * ```
 */
@Singleton
class MessageEnvelopeFormatter @Inject constructor() {

    /**
     * Format a channel message into an enriched envelope string.
     *
     * @param message The incoming channel message.
     * @param contextProviders Active device context providers.
     * @param customTemplate Optional custom template override.
     * @return The formatted message string to send to the agent.
     */
    suspend fun format(
        message: ChannelMessage,
        contextProviders: List<DeviceContextProvider> = emptyList(),
        memorySnapshot: RuntimeMemorySnapshot? = null,
        customTemplate: String? = null,
    ): String {
        if (customTemplate != null) {
            return applyTemplate(customTemplate, message, contextProviders, memorySnapshot)
        }

        val contextLines = buildList {
            add("Channel: ${message.channelId} | Sender: ${message.senderId}${message.senderName?.let { " ($it)" } ?: ""}")

            memorySnapshot?.renderLines()?.forEach { add(it) }

            for (provider in contextProviders) {
                if (provider.hasPermission()) {
                    provider.gatherContext()?.let { add(it) }
                }
            }

            if (message.attachments.isNotEmpty()) {
                val attachmentSummary = message.attachments.joinToString(", ") { att ->
                    "${att.name} (${att.mimeType})"
                }
                add("Attachments: $attachmentSummary")
            }
        }

        return if (contextLines.isNotEmpty()) {
            val reminder = contextLines.joinToString("\n")
            "<system-reminder>\n$reminder\n</system-reminder>\n${message.text}"
        } else {
            message.text
        }
    }

    private suspend fun applyTemplate(
        template: String,
        message: ChannelMessage,
        contextProviders: List<DeviceContextProvider>,
        memorySnapshot: RuntimeMemorySnapshot?,
    ): String {
        var result = template
        result = result.replace("{{channel}}", message.channelId)
        result = result.replace("{{sender}}", message.senderId)
        result = result.replace("{{sender_name}}", message.senderName ?: message.senderId)
        result = result.replace("{{text}}", message.text)
        result = result.replace("{{chat_id}}", message.chatId)
        result = result.replace("{{timestamp}}", message.timestamp.toString())

        // Replace {{context}} with gathered device context
        val contextBlock = contextProviders.mapNotNull { provider ->
            if (provider.hasPermission()) provider.gatherContext() else null
        }.joinToString("\n")
        result = result.replace("{{context}}", contextBlock)
        result = result.replace("{{memory}}", memorySnapshot?.renderLines()?.joinToString("\n") ?: "")

        return result
    }
}
