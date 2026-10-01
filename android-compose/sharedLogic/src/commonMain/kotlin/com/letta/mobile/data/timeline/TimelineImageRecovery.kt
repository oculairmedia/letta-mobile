package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.LettaMessage
import com.letta.mobile.data.model.MessageContentPart
import com.letta.mobile.util.Telemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Brings back the bytes of images this device kept only as size pointers (letta-mobile-a02be).
 *
 * Before image bodies were persisted, a snapshot stored an image over the inline thumbnail budget
 * as its size alone. The server still holds the message with its image content, so each such row
 * is fetched once: one `message.list` page starting right after the row before it. The bytes are
 * handed to [restore], which fills the placeholders and lets the next snapshot write persist them
 * as image bodies. Failures and misses leave the placeholder; a row is never asked for twice in
 * one loop's lifetime.
 */
internal class TimelineImageRecovery(
    private val conversationId: String,
    private val transport: TimelineTransport,
    private val timeline: () -> Timeline,
    private val restore: suspend (serverId: String, images: List<MessageContentPart.Image>) -> Boolean,
    private val maxAttempts: Int = MAX_ATTEMPTS_PER_LOOP,
) {
    private val mutex = Mutex()
    private val attempted = HashSet<String>()

    /** Recovers placeholders newest first. Returns how many rows got their images back. */
    suspend fun recover(): Int = mutex.withLock {
        val targets = timeline().imageRecoveryTargets()
            .filter { it.serverId !in attempted }
            .take((maxAttempts - attempted.size).coerceAtLeast(0))
        var restored = 0
        for (target in targets) {
            attempted += target.serverId
            val images = fetchImages(target) ?: continue
            if (restore(target.serverId, images)) restored++
        }
        if (targets.isNotEmpty()) {
            Telemetry.event(
                "TimelineSync", "imageRecovery.completed",
                "conversationId" to conversationId,
                "requested" to targets.size,
                "restored" to restored,
            )
        }
        restored
    }

    private suspend fun fetchImages(target: Target): List<MessageContentPart.Image>? {
        val page = try {
            transport.listConversationMessages(
                conversationId = conversationId,
                limit = PAGE_LIMIT,
                after = target.afterServerId,
                order = "asc",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Telemetry.error(
                "TimelineSync", "imageRecovery.fetchFailed", error,
                "conversationId" to conversationId,
                "serverId" to target.serverId,
            )
            return null
        }
        val match = page.firstOrNull { it.id == target.serverId }
            ?: page.firstOrNull { !it.otid.isNullOrBlank() && it.otid == target.otid }
        return match?.imagesWithBytes()?.takeIf { it.isNotEmpty() }
    }

    private fun LettaMessage.imagesWithBytes(): List<MessageContentPart.Image> =
        toTimelineEvent(position = 0.0)?.attachments?.filter { it.base64.isNotEmpty() }.orEmpty()

    internal data class Target(val serverId: String, val otid: String, val afterServerId: String?)

    internal companion object {
        /** Rows with a placeholder, newest first, each with the server id of the confirmed row before it. */
        fun Timeline.imageRecoveryTargets(): List<Target> {
            val targets = ArrayList<Target>()
            var previous: String? = null
            for (event in events) {
                val confirmed = event as? TimelineEvent.Confirmed ?: continue
                if (confirmed.attachments.any { it.isSizeOnlyPlaceholder }) {
                    targets += Target(confirmed.serverId, confirmed.otid, previous)
                }
                previous = confirmed.serverId
            }
            targets.reverse()
            return targets
        }

        /** Enough for the row after the cursor even when it is split into several messages. */
        const val PAGE_LIMIT = 8

        /** Bounds the requests one conversation can cost per session. */
        const val MAX_ATTEMPTS_PER_LOOP = 32
    }
}
