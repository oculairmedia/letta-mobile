package com.letta.mobile.data.transport

import com.letta.mobile.data.model.SubagentEntry
import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Tag

private val wireJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    coerceInputValues = true
}

// Dummy normalized event candidates for the routing contract.
sealed interface NormalizedPushEvent {
    val pushId: String
    val timestamp: String
    val reason: String

    data class CronsRefresh(
        override val pushId: String,
        override val timestamp: String,
        override val reason: String,
        val tasksActive: Long,
    ) : NormalizedPushEvent

    data class GoalsRefresh(
        override val pushId: String,
        override val timestamp: String,
        override val reason: String,
    ) : NormalizedPushEvent

    data class AgentRefresh(
        override val pushId: String,
        override val timestamp: String,
        override val reason: String,
        val agentId: String,
    ) : NormalizedPushEvent

    data class AgentDelete(
        override val pushId: String,
        override val timestamp: String,
        override val reason: String,
        val agentId: String,
    ) : NormalizedPushEvent

    data class SubagentsSnapshot(
        override val pushId: String,
        override val timestamp: String,
        override val reason: String,
        val changed: SubagentEntry?,
        val active: List<SubagentEntry>,
    ) : NormalizedPushEvent
}

// Spec-style mapping candidate.
fun mapToNormalizedEvent(frame: ServerFrame): NormalizedPushEvent? = when (frame) {
    is ServerFrame.CronsUpdated -> NormalizedPushEvent.CronsRefresh(
        pushId = frame.id,
        timestamp = frame.ts,
        reason = frame.reason,
        tasksActive = frame.tasksActive,
    )
    is ServerFrame.GoalsUpdated -> NormalizedPushEvent.GoalsRefresh(
        pushId = frame.id,
        timestamp = frame.ts,
        reason = frame.reason,
    )
    is ServerFrame.AgentUpdated -> if (frame.reason == "deleted") {
        NormalizedPushEvent.AgentDelete(
            pushId = frame.id,
            timestamp = frame.ts,
            reason = frame.reason,
            agentId = frame.agentId,
        )
    } else {
        NormalizedPushEvent.AgentRefresh(
            pushId = frame.id,
            timestamp = frame.ts,
            reason = frame.reason,
            agentId = frame.agentId,
        )
    }
    is ServerFrame.SubagentsUpdated -> NormalizedPushEvent.SubagentsSnapshot(
        pushId = frame.id,
        timestamp = frame.ts,
        reason = frame.reason,
        changed = frame.subagent,
        active = frame.subagentsActive,
    )
    else -> null
}

private fun decodePush(payload: String): NormalizedPushEvent? =
    mapToNormalizedEvent(wireJson.decodeFromString(ServerFrameSerializer, payload))

/**
 * letta-mobile-oqlmg: Test-only/spec-style coverage for frame parsing/mapping candidates
 * documenting the desired normalized routing contract for push-like mobile WS frames
 * (crons_updated, goals_updated, agent_updated, subagents_updated) before implementing
 * the full PushStateRouter architecture.
 */
@Tag("unit")
class PushStateRouterTest : WordSpec({

    "PushStateRouter frame mapping candidates" should {
        "decode and map crons_updated to CronsRefresh" {
            val event = decodePush(
                """
                {
                  "v": 1,
                  "type": "crons_updated",
                  "id": "push-1",
                  "ts": "2026-01-01T00:00:00Z",
                  "reason": "cron_tick",
                  "tasks_active": 5,
                  "at": "2026-01-01T00:00:00Z"
                }
                """.trimIndent(),
            )

            event shouldBe NormalizedPushEvent.CronsRefresh(
                pushId = "push-1",
                timestamp = "2026-01-01T00:00:00Z",
                reason = "cron_tick",
                tasksActive = 5L,
            )
        }

        "decode and map goals_updated to GoalsRefresh" {
            val event = decodePush(
                """
                {
                  "v": 1,
                  "type": "goals_updated",
                  "id": "push-2",
                  "ts": "2026-01-01T00:00:01Z",
                  "reason": "goal_completed",
                  "at": "2026-01-01T00:00:01Z"
                }
                """.trimIndent(),
            )

            event shouldBe NormalizedPushEvent.GoalsRefresh(
                pushId = "push-2",
                timestamp = "2026-01-01T00:00:01Z",
                reason = "goal_completed",
            )
        }

        "decode and map agent_updated to AgentRefresh" {
            val event = decodePush(
                """
                {
                  "v": 1,
                  "type": "agent_updated",
                  "id": "push-3",
                  "ts": "2026-01-01T00:00:02Z",
                  "agent_id": "agent-x",
                  "reason": "core_memory_append",
                  "at": "2026-01-01T00:00:02Z"
                }
                """.trimIndent(),
            )

            event shouldBe NormalizedPushEvent.AgentRefresh(
                pushId = "push-3",
                timestamp = "2026-01-01T00:00:02Z",
                reason = "core_memory_append",
                agentId = "agent-x",
            )
        }

        "decode and map deleted agent_updated to AgentDelete" {
            val event = decodePush(
                """
                {
                  "v": 1,
                  "type": "agent_updated",
                  "id": "push-4",
                  "ts": "2026-01-01T00:00:03Z",
                  "agent_id": "agent-x",
                  "reason": "deleted",
                  "at": "2026-01-01T00:00:03Z"
                }
                """.trimIndent(),
            )

            event shouldBe NormalizedPushEvent.AgentDelete(
                pushId = "push-4",
                timestamp = "2026-01-01T00:00:03Z",
                reason = "deleted",
                agentId = "agent-x",
            )
        }

        "decode and map subagents_updated to a full SubagentsSnapshot" {
            val event = decodePush(
                """
                {
                  "v": 1,
                  "type": "subagents_updated",
                  "id": "push-5",
                  "ts": "2026-01-01T00:00:04Z",
                  "reason": "subagent_started",
                  "subagent": {
                    "toolCallId": "tc_1",
                    "description": "Search docs",
                    "subagentType": "research",
                    "status": "running",
                    "subagentConversationId": "conv-sub",
                    "parentRunId": "run-parent"
                  },
                  "subagents_active": [
                    {
                      "toolCallId": "tc_1",
                      "description": "Search docs",
                      "subagentType": "research",
                      "status": "running",
                      "subagentConversationId": "conv-sub",
                      "parentRunId": "run-parent"
                    }
                  ],
                  "at": "2026-01-01T00:00:04Z"
                }
                """.trimIndent(),
            ) as NormalizedPushEvent.SubagentsSnapshot

            event.pushId shouldBe "push-5"
            event.timestamp shouldBe "2026-01-01T00:00:04Z"
            event.reason shouldBe "subagent_started"
            event.changed shouldBe SubagentEntry(
                toolCallId = "tc_1",
                description = "Search docs",
                subagentType = "research",
                status = "running",
                subagentConversationId = "conv-sub",
                parentRunId = "run-parent",
            )
            event.active shouldContainExactly listOf(
                SubagentEntry(
                    toolCallId = "tc_1",
                    description = "Search docs",
                    subagentType = "research",
                    status = "running",
                    subagentConversationId = "conv-sub",
                    parentRunId = "run-parent",
                ),
            )
        }

        "return null for unrelated ServerFrames" {
            val frame = ServerFrame.Unknown(
                id = "un-1",
                ts = "2026-01-01T00:00:00Z",
                type = "some_unknown_push",
                raw = JsonObject(emptyMap()),
            )

            mapToNormalizedEvent(frame) shouldBe null
        }

        "return null for unrecognized frame types through decodePush" {
            // Route an unknown wire-type through the full deserialize+map path
            // so a serializer regression that fails to produce ServerFrame.Unknown
            // would surface here (the bare mapToNormalizedEvent case does not).
            val event = decodePush(
                """{ "v": 1, "type": "some_unknown_push", "id": "un-2", "ts": "2026-01-01T00:00:00Z" }"""
            )
            event shouldBe null
        }
    }
})
