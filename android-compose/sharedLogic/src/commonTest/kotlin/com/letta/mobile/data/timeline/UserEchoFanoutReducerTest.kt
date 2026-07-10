package com.letta.mobile.data.timeline

import com.letta.mobile.data.model.AssistantMessage
import com.letta.mobile.data.model.LettaMessageSerializer
import com.letta.mobile.data.model.MessageContentPart
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * eaczz.5 (S5-T): live user-echo fanout — reducer-level, two-connection contract.
 *
 * WHY THE REDUCER SEAM (not the fanout seam): the S5 acceptance criteria are all
 * about how the two peers' RENDER/REDUCE MODELS end up after the sender's user
 * echo lands — (a) observer gains exactly one user row before the assistant row,
 * (b) initiator does NOT double-render its optimistic row, (c) image content-parts
 * are carried, (d) replay is idempotent. Those are reducer invariants. This test
 * injects the EXACT wire `user_message` delta [ConversationTurnFanout.broadcastUserEcho]
 * emits (deserialized through the real [LettaMessageSerializer], the same path the
 * observer's transport uses) into two separate [Timeline]s and reduces, so it
 * pins the wire-frame -> reducer contract end to end. The fanout-seam shape/order
 * (delta id scheme, not-parked, before-assistant ordering) is covered separately
 * in ConversationTurnFanoutUserEchoTest (jvmTest).
 *
 * DEDUP ID SCHEME under test: the echo carries otid == the sender's
 * clientMessageId and a stable server id `cm-user-<otid>`. On the initiator the
 * reducer collapses the echo against the optimistic Local row that shares that
 * otid (Timeline.identityKeys -> "otid:<otid>"); on the observer it appends once
 * and a replay is dropped by the same identity key.
 */
class UserEchoFanoutReducerTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Exactly what ConversationTurnFanout.broadcastUserEcho puts on the wire. */
    private fun userEchoFrameJson(
        clientMessageId: String,
        text: String,
        contentPartsJson: String? = null,
    ): String = buildJsonObject {
        put("message_type", "user_message")
        put("id", "cm-user-$clientMessageId")
        put("otid", clientMessageId)
        put("seq_id", 0)
        if (contentPartsJson != null) {
            put("content", json.parseToJsonElement(contentPartsJson))
        } else {
            put("content", text)
        }
    }.toString()

    private fun decodeUserEcho(frameJson: String) =
        json.decodeFromString(LettaMessageSerializer, frameJson)

    private fun reduce(prev: Timeline, frameJson: String): Timeline =
        reduceStreamFrame(
            TimelineReducerInput(
                prev = prev,
                frame = decodeUserEcho(frameJson),
                pendingToolReturnsByCallId = persistentMapOf(),
            )
        ).next

    private fun reduceAssistant(prev: Timeline, id: String, otid: String, content: String): Timeline =
        reduceStreamFrame(
            TimelineReducerInput(
                prev = prev,
                frame = AssistantMessage(id = id, contentRaw = JsonPrimitive(content), otid = otid, seqId = 1),
                pendingToolReturnsByCallId = persistentMapOf(),
            )
        ).next

    private fun emptyTimeline() = Timeline(conversationId = "conv-echo")

    /** The sender's optimistic Local user row: same otid as the echo's clientMessageId. */
    private fun initiatorWithOptimisticRow(otid: String, text: String): Timeline =
        emptyTimeline().append(
            TimelineEvent.Local(
                position = 1.0,
                otid = otid,
                content = text,
                sentAt = timelineNow(),
                deliveryState = DeliveryState.SENDING,
            )
        )

    @Test
    fun observerGainsExactlyOneUserRowBeforeAssistant() {
        val otid = "cm-otid-observer"
        // Observer has NO optimistic row (it did not send). Echo then assistant.
        var observer = emptyTimeline()
        observer = reduce(observer, userEchoFrameJson(otid, "hello there"))
        observer = reduceAssistant(observer, id = "letta-msg-1", otid = "assistant-otid", content = "Hi!")

        val userRows = observer.events.filter {
            it is TimelineEvent.Confirmed && it.messageType == TimelineMessageType.USER
        }
        assertEquals(1, userRows.size, "observer must gain exactly one user row")
        val userRow = userRows.single() as TimelineEvent.Confirmed
        assertEquals("hello there", userRow.content)

        // User row is positioned BEFORE the assistant row.
        val userIdx = observer.events.indexOf(userRow)
        val assistantIdx = observer.events.indexOfFirst {
            it is TimelineEvent.Confirmed && it.messageType == TimelineMessageType.ASSISTANT
        }
        assertTrue(assistantIdx >= 0, "assistant row present")
        assertTrue(userIdx < assistantIdx, "user row must precede assistant row")
    }

    @Test
    fun initiatorDoesNotDoubleRenderItsOptimisticUserRow() {
        val otid = "cm-otid-initiator"
        // Initiator already holds its optimistic Local user row (same otid).
        var initiator = initiatorWithOptimisticRow(otid, "hello there")
        // The broadcast echo comes back to the initiator carrying that otid.
        initiator = reduce(initiator, userEchoFrameJson(otid, "hello there"))

        val userRows = initiator.events.filter {
            (it is TimelineEvent.Confirmed && it.messageType == TimelineMessageType.USER) ||
                (it is TimelineEvent.Local && it.role == Role.USER)
        }
        assertEquals(1, userRows.size, "initiator must keep exactly one user row (optimistic + echo collapse)")
    }

    @Test
    fun replayingUserEchoIsIdempotent() {
        val otid = "cm-otid-idem"
        var observer = emptyTimeline()
        observer = reduce(observer, userEchoFrameJson(otid, "hello"))
        // Replay the IDENTICAL echo frame — must NOT append a second row
        // (no "hellohello"; snapshot, not append).
        observer = reduce(observer, userEchoFrameJson(otid, "hello"))

        val userRows = observer.events.filter {
            it is TimelineEvent.Confirmed && it.messageType == TimelineMessageType.USER
        }
        assertEquals(1, userRows.size, "replayed echo must be idempotent")
        assertEquals("hello", (userRows.single() as TimelineEvent.Confirmed).content)
    }

    @Test
    fun imageContentPartsAreCarriedToObservers() {
        val otid = "cm-otid-image"
        // The initiator's content-parts array (text + image), forwarded verbatim.
        val contentParts = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", "look at this")
            })
            add(buildJsonObject {
                put("type", "image")
                put("source", buildJsonObject {
                    put("type", "base64")
                    put("media_type", "image/png")
                    put("data", "AAAABBBBCCCC==")
                })
            })
        }.toString()

        var observer = emptyTimeline()
        observer = reduce(observer, userEchoFrameJson(otid, "look at this", contentPartsJson = contentParts))

        val userRow = observer.events.single {
            it is TimelineEvent.Confirmed && it.messageType == TimelineMessageType.USER
        } as TimelineEvent.Confirmed
        assertEquals("look at this", userRow.content, "text part carried")
        assertEquals(
            listOf(MessageContentPart.Image(base64 = "AAAABBBBCCCC==", mediaType = "image/png")),
            userRow.attachments,
            "image content-part carried to observer",
        )
    }
}
