package com.letta.mobile.data.model

import kotlin.time.Instant

/**
 * letta-mobile-by4vm.72: the one authoritative "newest conversation first" order.
 *
 * A conversation's recency is the first PARSEABLE of `last_message_at`, `updated_at`,
 * `created_at` as epoch millis (0 when none parse, so it sorts last); `id` ascending breaks
 * exact ties so the order is total and stable. Every surface that orders conversations uses
 * this: the Room cache stores [epochMillisOrNull] in its epoch columns and its SQL
 * (`COALESCE(lastMessageAtEpochMs, updatedAtEpochMs, createdAtEpochMs, 0) DESC, id`) is the
 * SQL form of [sortKeyMillis]; the iroh host's on-disk `conversation.list` reader sorts with
 * [comparator]. The upstream App Server's own local list applies the same field fallback but
 * compares ISO strings; its own records are `toISOString()` (always `Z`, millisecond fraction),
 * for which string and instant order coincide.
 *
 * Timestamps arrive with `Z` or `+00:00`/`+02:00` offsets, any fraction precision, and
 * occasionally offset-less (assumed UTC) or space-separated, so string comparison is wrong.
 */
object ConversationRecency {
    private const val DATE_TIME_SEPARATOR_INDEX = 10

    /** Epoch millis of an ISO-8601 timestamp, or null when [raw] is blank or unparseable. */
    fun epochMillisOrNull(raw: String?): Long? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() }?.let(::withIsoSeparator) ?: return null
        return parseOrNull(value) ?: parseOrNull("${value}Z")
    }

    /** The recency of one conversation's three timestamps (0 when none parse). */
    fun sortKeyMillis(lastMessageAt: String?, updatedAt: String?, createdAt: String?): Long =
        epochMillisOrNull(lastMessageAt) ?: epochMillisOrNull(updatedAt) ?: epochMillisOrNull(createdAt) ?: 0L

    /** Newest first by [sortKeyMillis], then [id] ascending, for any row shape. */
    fun <T> comparator(
        id: (T) -> String,
        lastMessageAt: (T) -> String?,
        updatedAt: (T) -> String?,
        createdAt: (T) -> String?,
    ): Comparator<T> =
        compareByDescending<T> { sortKeyMillis(lastMessageAt(it), updatedAt(it), createdAt(it)) }.thenBy(id)

    /** [comparator] over the API [Conversation] model. */
    val newestFirst: Comparator<Conversation> = comparator(
        id = { it.id.value },
        lastMessageAt = Conversation::lastMessageAt,
        updatedAt = Conversation::updatedAt,
        createdAt = Conversation::createdAt,
    )

    private fun withIsoSeparator(value: String): String =
        if (value.length > DATE_TIME_SEPARATOR_INDEX && value[DATE_TIME_SEPARATOR_INDEX] == ' ') {
            value.replaceRange(DATE_TIME_SEPARATOR_INDEX, DATE_TIME_SEPARATOR_INDEX + 1, "T")
        } else {
            value
        }

    private fun parseOrNull(value: String): Long? =
        runCatching { Instant.parse(value).toEpochMilliseconds() }.getOrNull()
}
