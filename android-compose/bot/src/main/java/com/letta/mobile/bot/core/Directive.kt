package com.letta.mobile.bot.core

/**
 * Directives embedded in agent responses that trigger side effects.
 * Maps to lettabot's directive system (<send-file>, <no-reply/>, <voice>, <actions>).
 *
 * The agent's raw text response is parsed for these XML-like directives,
 * which are then stripped from the display text and executed separately.
 */
sealed interface Directive {

    /**
     * Instructs the bot to send a file to the channel.
     * Lettabot equivalent: `<send-file path="..." name="..." />`.
     */
    data class SendFile(
        val path: String,
        val name: String? = null,
        val mimeType: String? = null,
    ) : Directive

    /**
     * Suppresses the response — the bot should NOT send any text reply.
     * Lettabot equivalent: `<no-reply/>`.
     */
    data object NoReply : Directive

    /**
     * Instructs the bot to generate and send a voice message.
     * Lettabot equivalent: `<voice>text to speak</voice>`.
     */
    data class Voice(
        val text: String,
    ) : Directive

    /**
     * A reaction to add to the source message (emoji, etc.).
     * Lettabot equivalent: `<actions><react emoji="..." /></actions>`.
     */
    data class React(
        val emoji: String,
    ) : Directive

    /**
     * Instructs the bot to delete the source message.
     * Lettabot equivalent: `<actions><delete-trigger/></actions>`.
     */
    data object DeleteTrigger : Directive

    /**
     * Instructs the bot to pin the response message.
     * Lettabot equivalent: `<actions><pin/></actions>`.
     */
    data object Pin : Directive
}
