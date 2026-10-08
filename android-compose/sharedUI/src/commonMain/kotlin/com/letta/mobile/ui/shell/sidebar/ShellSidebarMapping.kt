package com.letta.mobile.ui.shell.sidebar

import com.letta.mobile.data.chat.runtime.ChatConversationSummary
import com.letta.mobile.data.chat.runtime.PinnedConversations
import com.letta.mobile.data.chat.runtime.displayTitle
import kotlin.time.Instant

/** Which conversations a host marks: the open one, the one whose agent is working, those being deleted. */
data class ShellConversationMarks(
    val selectedId: String? = null,
    val thinkingId: String? = null,
    val deletingIds: Set<String> = emptySet(),
    /** letta-mobile-bzvro.17: pinned conversations, listed first. */
    val pinnedIds: Set<String> = emptySet(),
)

/** Pure mapping from the platform-neutral conversation summaries to the panel's rows. */
object ShellSidebarMapping {
    /** The placeholder preview a conversation carries before its messages load; never shown. */
    private const val LOADING_PREVIEW = "Loaded from backend"

    /**
     * [conversations] as panel rows, the pinned ones first (each group keeps its order).
     * [timeLabel] turns a summary's `updatedAtLabel` into the row's trailing time (see
     * [ShellRelativeTime.compact]).
     */
    fun conversationRows(
        conversations: List<ChatConversationSummary>,
        marks: ShellConversationMarks,
        timeLabel: (String) -> String,
    ): List<ShellConversationRowModel> =
        PinnedConversations.pinnedFirst(conversations, marks.pinnedIds) { it.id }.map { conversation ->
            ShellConversationRowModel(
                id = conversation.id,
                title = conversation.displayTitle(),
                preview = cleanPreview(conversation.lastMessagePreview),
                timeLabel = timeLabel(conversation.updatedAtLabel),
                selected = conversation.id == marks.selectedId,
                thinking = conversation.id == marks.thinkingId,
                deleting = conversation.id in marks.deletingIds,
                archived = conversation.archived,
                pinned = conversation.id in marks.pinnedIds,
            )
        }

    /** The preview line under a title: trimmed, and blank for the loading placeholder. */
    fun cleanPreview(raw: String): String =
        raw.trim().takeUnless { it.equals(LOADING_PREVIEW, ignoreCase = true) }.orEmpty()
}

/** Compact relative time labels for list rows: now / 5m / 2h / 4d / 3w / 2mo. */
object ShellRelativeTime {
    private const val MINUTE = 60L
    private const val HOUR = 3_600L
    private const val DAY = 86_400L
    private const val WEEK = 604_800L
    private const val MONTH = 2_592_000L

    /** [raw] (an ISO-8601 instant) relative to [now]; text that is not an instant comes back unchanged. */
    fun compact(raw: String, now: Instant): String {
        val instant = runCatching { Instant.parse(raw) }.getOrNull() ?: return raw
        return compact(instant, now)
    }

    /** [instant] relative to [now]; a time in the future reads "now". */
    fun compact(instant: Instant, now: Instant): String {
        val seconds = (now - instant).inWholeSeconds
        return when {
            seconds < MINUTE -> "now"
            seconds < HOUR -> "${seconds / MINUTE}m"
            seconds < DAY -> "${seconds / HOUR}h"
            seconds < WEEK -> "${seconds / DAY}d"
            seconds < MONTH -> "${seconds / WEEK}w"
            else -> "${seconds / MONTH}mo"
        }
    }
}
