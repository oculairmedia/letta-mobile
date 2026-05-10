package com.letta.mobile.channel

/**
 * Normalizes notification text before it reaches Android's collapsed shade.
 *
 * Streaming transports can briefly surface empty or low-signal previews (for
 * example a single leading token such as "I"). Those snippets make the
 * notification look blank or broken, so callers should fall back to safe copy
 * whenever [fallbackReason] is not [NotificationTextFallbackReason.None].
 */
internal object NotificationContentSanitizer {
    private const val MIN_PREVIEW_SIGNAL_CHARS = 2
    private val whitespaceRun = Regex("\\s+")

    fun sanitizeTitle(raw: String, fallback: String): SanitizedNotificationText {
        val normalized = normalize(raw)
        val reason = when {
            normalized.isBlank() -> NotificationTextFallbackReason.Blank
            normalized.hasMeaningfulSignal().not() -> NotificationTextFallbackReason.PunctuationOnly
            else -> NotificationTextFallbackReason.None
        }
        return SanitizedNotificationText(
            text = if (reason == NotificationTextFallbackReason.None) normalized else fallback,
            rawLength = raw.length,
            normalizedLength = normalized.length,
            fallbackReason = reason,
        )
    }

    fun sanitizePreview(raw: String, fallback: String? = null): SanitizedNotificationText {
        val normalized = normalize(raw)
        val reason = when {
            normalized.isBlank() -> NotificationTextFallbackReason.Blank
            normalized.length < MIN_PREVIEW_SIGNAL_CHARS -> NotificationTextFallbackReason.TooShort
            normalized.hasMeaningfulSignal().not() -> NotificationTextFallbackReason.PunctuationOnly
            else -> NotificationTextFallbackReason.None
        }
        return SanitizedNotificationText(
            text = if (reason == NotificationTextFallbackReason.None) normalized else fallback ?: normalized,
            rawLength = raw.length,
            normalizedLength = normalized.length,
            fallbackReason = reason,
        )
    }

    private fun normalize(raw: String): String = raw
        .map { if (it.isISOControl()) ' ' else it }
        .joinToString(separator = "")
        .trim()
        .replace(whitespaceRun, " ")

    private fun String.hasMeaningfulSignal(): Boolean = any { char ->
        !char.isWhitespace() && !char.isISOControl() && !char.isPunctuation()
    }

    private fun Char.isPunctuation(): Boolean = when (Character.getType(this)) {
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt(),
        -> true
        else -> false
    }
}

internal data class SanitizedNotificationText(
    val text: String,
    val rawLength: Int,
    val normalizedLength: Int,
    val fallbackReason: NotificationTextFallbackReason,
) {
    val fallbackApplied: Boolean
        get() = fallbackReason != NotificationTextFallbackReason.None
}

internal enum class NotificationTextFallbackReason {
    None,
    Blank,
    TooShort,
    PunctuationOnly,
}
