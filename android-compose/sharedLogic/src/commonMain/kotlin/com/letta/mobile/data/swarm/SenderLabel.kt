package com.letta.mobile.data.swarm

import kotlin.jvm.JvmInline

/**
 * A short, display-only label naming who sent a swarm message ("orchestrator").
 * It is NOT an identity: anyone holding a paired device can write any label, so the agent must not
 * trust it. The authoritative sender is the device NodeId the host stamps.
 * Control characters and line breaks are dropped so a label can never forge further lines.
 */
@JvmInline
value class SenderLabel private constructor(val value: String) {
    override fun toString(): String = value

    companion object {
        const val MAX_CHARS: Int = 48

        /** The sanitized label, or null when nothing printable is left. */
        fun of(raw: String): SenderLabel? = raw
            .filterNot { it.isISOControl() }
            .replace(WHITESPACE, " ")
            .trim()
            .take(MAX_CHARS)
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let(::SenderLabel)

        private val WHITESPACE = Regex("\\s+")
    }
}

/** The text the agent receives: a one-line sender header (when [from] is set), a blank line, then the message. */
fun String.withSenderLine(from: SenderLabel?): String =
    if (from == null) this else "$SENDER_PREFIX${from.value}$SENDER_SUFFIX\n\n$this"

/** Prefix of the header line; the suffix says the label is self-declared. */
const val SENDER_PREFIX: String = "[from: "
const val SENDER_SUFFIX: String = " (self-declared, via meridian cli)]"
