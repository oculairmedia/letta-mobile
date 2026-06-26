package com.letta.mobile.data.transport

import com.letta.mobile.data.model.SubagentEntry
import com.letta.mobile.data.transport.ServerFrame.*
import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Tag
import kotlinx.serialization.json.JsonObject

// Dummy normalized event candidates for the routing contract
sealed interface NormalizedPushEvent {
    val pushId: String
    val timestamp: String
    val reason: String
    
    data class CronsRefresh(
        override val pushId: String,
        override val timestamp: String,
        override val reason: String,
        val tasksActive: Long
    ) : NormalizedPushEvent

    data class GoalsRefresh(
        override val pushId: String,
        override val timestamp: String,
        override val reason: String
    ) : NormalizedPushEvent
    
    data class AgentRefresh(
        override val pushId: String,
        override val timestamp: String,
        override val reason: String,
        val agentId: String
    ) : NormalizedPushEvent

    data class SubagentsRefresh(
        override val pushId: String,
        override val timestamp: String,
        override val reason: String,
        val activeCount: Int
    ) : NormalizedPushEvent
}

// Spec-style mapping candidate
fun mapToNormalizedEvent(frame: ServerFrame): NormalizedPushEvent? = when (frame) {
    is CronsUpdated -> NormalizedPushEvent.CronsRefresh(
        pushId = frame.id,
        timestamp = frame.ts,
        reason = frame.reason,
        tasksActive = frame.tasksActive
    )
    is GoalsUpdated -> NormalizedPushEvent.GoalsRefresh(
        pushId = frame.id,
        timestamp = frame.ts,
        reason = frame.reason
    )
    is AgentUpdated -> NormalizedPushEvent.AgentRefresh(
        pushId = frame.id,
        timestamp = frame.ts,
        reason = frame.reason,
        agentId = frame.agentId
    )
    is SubagentsUpdated -> NormalizedPushEvent.SubagentsRefresh(
        pushId = frame.id,
        timestamp = frame.ts,
        reason = frame.reason,
        activeCount = frame.subagentsActive.size
    )
    else -> null
}

/**
 * letta-mobile-oqlmg: Test-only/spec-style coverage for frame parsing/mapping candidates
 * documenting the desired normalized routing contract for push-like mobile WS frames
 * (crons_updated, goals_updated, agent_updated, subagents_updated) before implementing
 * the full PushStateRouter architecture.
 */
@Tag("unit")
class PushStateRouterTest : WordSpec({

    "PushStateRouter frame mapping candidates" should {
        "map CronsUpdated to NormalizedPushEvent.CronsRefresh" {
            val frame = CronsUpdated(
                id = "push-1",
                ts = "2026-01-01T00:00:00Z",
                reason = "cron_tick",
                tasksActive = 5L,
                at = "2026-01-01T00:00:00Z"
            )
            val event = mapToNormalizedEvent(frame)
            
            event shouldBe NormalizedPushEvent.CronsRefresh(
                pushId = "push-1",
                timestamp = "2026-01-01T00:00:00Z",
                reason = "cron_tick",
                tasksActive = 5L
            )
        }

        "map GoalsUpdated to NormalizedPushEvent.GoalsRefresh" {
            val frame = GoalsUpdated(
                id = "push-2",
                ts = "2026-01-01T00:00:01Z",
                reason = "goal_completed",
                at = "2026-01-01T00:00:01Z"
            )
            val event = mapToNormalizedEvent(frame)
            
            event shouldBe NormalizedPushEvent.GoalsRefresh(
                pushId = "push-2",
                timestamp = "2026-01-01T00:00:01Z",
                reason = "goal_completed"
            )
        }

        "map AgentUpdated to NormalizedPushEvent.AgentRefresh" {
            val frame = AgentUpdated(
                id = "push-3",
                ts = "2026-01-01T00:00:02Z",
                agentId = "agent-x",
                reason = "core_memory_append",
                at = "2026-01-01T00:00:02Z"
            )
            val event = mapToNormalizedEvent(frame)
            
            event shouldBe NormalizedPushEvent.AgentRefresh(
                pushId = "push-3",
                timestamp = "2026-01-01T00:00:02Z",
                reason = "core_memory_append",
                agentId = "agent-x"
            )
        }

        "map SubagentsUpdated to NormalizedPushEvent.SubagentsRefresh" {
            val frame = SubagentsUpdated(
                id = "push-4",
                ts = "2026-01-01T00:00:03Z",
                reason = "subagent_started",
                subagentsActive = listOf(
                    SubagentEntry(
                        toolCallId = "tc_1",
                        status = "running"
                    )
                ),
                at = "2026-01-01T00:00:03Z"
            )
            val event = mapToNormalizedEvent(frame)
            
            event shouldBe NormalizedPushEvent.SubagentsRefresh(
                pushId = "push-4",
                timestamp = "2026-01-01T00:00:03Z",
                reason = "subagent_started",
                activeCount = 1
            )
        }
        
        "return null for unrelated ServerFrames" {
            val frame = Unknown(
                id = "un-1",
                ts = "2026-01-01T00:00:00Z",
                type = "some_unknown_push",
                raw = JsonObject(emptyMap())
            )
            val event = mapToNormalizedEvent(frame)
            
            event shouldBe null
        }
    }
})
