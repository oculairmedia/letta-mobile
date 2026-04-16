package com.letta.mobile.bot.message

import com.letta.mobile.bot.channel.ChannelMessage
import com.letta.mobile.bot.context.DeviceContextProvider
import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking

class MessageEnvelopeFormatterTest : WordSpec({
    "MessageEnvelopeFormatter" should {
        "include skill reminders in the default envelope output" {
            val formatter = MessageEnvelopeFormatter()

            val result = runBlocking {
                formatter.format(
                    message = ChannelMessage(
                        messageId = "msg-1",
                        channelId = "in_app",
                        chatId = "chat-1",
                        senderId = "user-1",
                        senderName = "Alex",
                        text = "Give me a briefing",
                    ),
                    contextProviders = listOf(
                        FakeEnvelopeContextProvider(
                            providerId = "battery",
                            displayName = "Battery",
                            context = "Battery: 80%",
                        )
                    ),
                    skillPromptFragments = listOf(
                        "[Morning Briefing]\nSummarize the day in three bullets.",
                        "[Commute Assistant]\nSurface delays only when they matter.",
                    ),
                )
            }

            result shouldContain "<system-reminder>"
            result shouldContain "Battery: 80%"
            result shouldContain "<skill-reminder>"
            result shouldContain "[Morning Briefing]"
            result shouldContain "[Commute Assistant]"
            result shouldContain "Give me a briefing"
        }

        "inject skill fragments into custom templates via the skills placeholder" {
            val formatter = MessageEnvelopeFormatter()

            val result = runBlocking {
                formatter.format(
                    message = ChannelMessage(
                        messageId = "msg-2",
                        channelId = "sms",
                        chatId = "chat-2",
                        senderId = "user-2",
                        text = "What changed?",
                    ),
                    customTemplate = "Context:\n{{context}}\nSkills:\n{{skills}}\nBody:\n{{text}}",
                    contextProviders = listOf(
                        FakeEnvelopeContextProvider(
                            providerId = "time",
                            displayName = "Time",
                            context = "Current time: 9:00 AM",
                        )
                    ),
                    skillPromptFragments = listOf("[Kotlin Developer]\nPrefer idiomatic Kotlin."),
                )
            }

            result shouldContain "Context:\nCurrent time: 9:00 AM"
            result shouldContain "Skills:\n[Kotlin Developer]\nPrefer idiomatic Kotlin."
            result shouldContain "Body:\nWhat changed?"
            result shouldNotContain "{{skills}}"
        }
    }
})

private data class FakeEnvelopeContextProvider(
    override val providerId: String,
    override val displayName: String,
    private val context: String?,
    private val hasPermission: Boolean = true,
) : DeviceContextProvider {
    override val requiredPermissions: List<String> = emptyList()

    override suspend fun gatherContext(): String? = context

    override suspend fun hasPermission(): Boolean = hasPermission
}
