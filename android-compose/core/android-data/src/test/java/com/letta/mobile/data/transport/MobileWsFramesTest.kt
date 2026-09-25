package com.letta.mobile.data.transport

import com.letta.mobile.data.a2ui.A2uiMessage
import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag

/**
 * letta-mobile-9vgk: lock the wire shapes of
 * [ServerFrame] against the contracts in
 * `admin-shim/docs/MOBILE_WS_PROTOCOL.md`. Each test name cites the
 * spec section being defended.
 */
@Tag("unit")
class MobileWsFramesTest : WordSpec({

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
    }

    "ServerFrame deserialization" should {
        "spec §2.2 welcome — exposes server_id / session_id / device_id" {
            val payload = """
                {"v":1,"type":"welcome","id":"f1","ts":"t",
                 "server_id":"S","session_id":"sess-1","device_id":"d-1",
                 "canonical_live_transport":"ws"}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.Welcome>()
            parsed.serverId shouldBe "S"
            parsed.sessionId shouldBe "sess-1"
            parsed.deviceId shouldBe "d-1"
            parsed.canonicalLiveTransport shouldBe "ws"
        }

        "letta-mobile-51xm.2 welcome — parses A2UI negotiation ack (§2.2)" {
            val payload = """
                {"v":1,"type":"welcome","id":"f1","ts":"t",
                 "server_id":"S","session_id":"sess-1",
                 "a2ui_negotiated":true,
                 "a2ui":{"version":"0.9","catalog_id":"basic"}}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.Welcome>()
            parsed.a2uiNegotiated shouldBe true
            parsed.a2ui?.version shouldBe "0.9"
            parsed.a2ui?.catalogId shouldBe "basic"
        }

        "spec §2.2 a2ui_capabilities — captures server-advertised catalog + widgets" {
            val payload = """
                {"v":1,"type":"a2ui_capabilities","id":"caps-1","ts":"t",
                 "version":"0.9","catalog_id":"basic",
                 "supported_catalogs":["basic"],
                 "supported_widgets":["Text","Button","ToolApprovalCard"]}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.A2uiCapabilities>()
            parsed.version shouldBe "0.9"
            parsed.catalogId shouldBe "basic"
            parsed.supportedWidgets shouldBe listOf("Text", "Button", "ToolApprovalCard")
        }

        "spec §2.2 user_action_ack — accepted has null reason" {
            val payload = """
                {"v":1,"type":"user_action_ack","id":"f","ts":"t",
                 "action_id":"act-1","status":"accepted"}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.UserActionAck>()
            parsed.actionId shouldBe "act-1"
            parsed.status shouldBe "accepted"
            parsed.reason shouldBe null
        }

        "lcp-uo5.14 user_action_outcome — parses all outcome values and frameId correlation" {
            val outcomes = listOf(
                "matched_approval",
                "injected_as_input",
                "recorded_only",
                "rejected",
                "error",
            )

            outcomes.forEach { outcome ->
                val payload = """
                    {"v":1,"type":"user_action_outcome","id":"out-$outcome","ts":"t",
                     "frameId":"frame-$outcome","action_id":"action-$outcome",
                     "outcome":"$outcome","reason":"reason-$outcome","idempotent":true,
                     "agent_id":"agent-1","conversation_id":"conv-1"}
                """.trimIndent()
                val parsed = json.decodeFromString(ServerFrameSerializer, payload)
                parsed.shouldBeInstanceOf<ServerFrame.UserActionOutcome>()
                parsed.frameId shouldBe "frame-$outcome"
                parsed.actionId shouldBe "action-$outcome"
                parsed.outcome shouldBe outcome
                parsed.reason shouldBe "reason-$outcome"
                parsed.idempotent shouldBe true
                parsed.agentId shouldBe "agent-1"
                parsed.conversationId shouldBe "conv-1"
            }
        }

        "lcp-uo5.14 user_action_outcome — accepts snake_case frame_id fallback" {
            val payload = """
                {"v":1,"type":"user_action_outcome","id":"out-1","ts":"t",
                 "frame_id":"frame-snake","outcome":"injected_as_input"}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.UserActionOutcome>()
            parsed.frameId shouldBe "frame-snake"
            parsed.outcome shouldBe "injected_as_input"
        }

        "spec §4.7 stop_reason — inner field is `stop_reason`, NOT `reason`" {
            val payload = """
                {"v":1,"type":"stop_reason","id":"f","ts":"t",
                 "turn_id":"T","run_id":"R","stop_reason":"end_turn"}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.StopReason>()
            parsed.stopReason shouldBe "end_turn"
        }

        "spec §4.4 usage_statistics — first-frame counters default to 0" {
            val payload = """
                {"v":1,"type":"usage_statistics","id":"f","ts":"t",
                 "turn_id":"T","run_id":"R","prompt_tokens":12,"seq":19}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.UsageStatistics>()
            parsed.promptTokens shouldBe 12L
            parsed.completionTokens shouldBe 0L
            parsed.totalTokens shouldBe 0L
            parsed.seq shouldBe 19L
        }

        "letta-mobile-ipp8z tolerates SDK-Transport assistant frames missing mobile metadata" {
            val payload = """
                {"v":1,"type":"assistant_message","id":"cm-stream-1","content":"hello"}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.AssistantMessage>()
            parsed.ts shouldBe ""
            parsed.agentId shouldBe null
            parsed.conversationId shouldBe null
            parsed.turnId shouldBe null
            parsed.runId shouldBe null
            parsed.content shouldBe "hello"
        }

        "letta-mobile-ipp8z tolerates SDK-Transport stop and usage frames missing mobile metadata" {
            val stop = json.decodeFromString(
                ServerFrameSerializer,
                """
                {"v":1,"type":"stop_reason","stop_reason":"end_turn"}
                """.trimIndent(),
            )
            stop.shouldBeInstanceOf<ServerFrame.StopReason>()
            stop.id shouldBe ""
            stop.ts shouldBe ""
            stop.turnId shouldBe null
            stop.runId shouldBe null
            stop.stopReason shouldBe "end_turn"

            val usage = json.decodeFromString(
                ServerFrameSerializer,
                """
                {"v":1,"type":"usage_statistics","prompt_tokens":12,"total_tokens":18}
                """.trimIndent(),
            )
            usage.shouldBeInstanceOf<ServerFrame.UsageStatistics>()
            usage.id shouldBe ""
            usage.ts shouldBe ""
            usage.turnId shouldBe null
            usage.runId shouldBe null
            usage.promptTokens shouldBe 12L
            usage.totalTokens shouldBe 18L
        }

        "letta-mobile-2rkdj assistant and reasoning frames surface run seq metadata" {
            val assistant = json.decodeFromString(
                ServerFrameSerializer,
                """
                {"v":1,"type":"assistant_message","id":"cm-stream-1","ts":"t",
                 "agent_id":"a","conversation_id":"c","turn_id":"T","run_id":"R",
                 "content":"hello","seq":17,"seq_id":17}
                """.trimIndent(),
            )
            assistant.shouldBeInstanceOf<ServerFrame.AssistantMessage>()
            assistant.seq shouldBe 17L
            assistant.seqId shouldBe 17

            val reasoning = json.decodeFromString(
                ServerFrameSerializer,
                """
                {"v":1,"type":"reasoning_message","id":"reason-1","ts":"t",
                 "agent_id":"a","conversation_id":"c","turn_id":"T","run_id":"R",
                 "reasoning":"thinking","seq":18,"seq_id":18}
                """.trimIndent(),
            )
            reasoning.shouldBeInstanceOf<ServerFrame.ReasoningMessage>()
            reasoning.seq shouldBe 18L
            reasoning.seqId shouldBe 18
        }

        "letta-mobile-2rkdj cursor_expired error keeps resume metadata" {
            val payload = """
                {"v":1,"type":"error","id":"err-cursor","ts":"t",
                 "code":"cursor_expired","message":"cursor too old",
                 "conversation_id":"conv-1","after_seq":2,"oldest_seq":10,"last_seq":20}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.Error>()
            parsed.code shouldBe "cursor_expired"
            parsed.conversationId shouldBe "conv-1"
            parsed.afterSeq shouldBe 2L
            parsed.oldestSeq shouldBe 10L
            parsed.lastSeq shouldBe 20L
        }

        "spec §4.1 approval_request_message folds into ToolCallMessage" {
            val payload = """
                {"v":1,"type":"approval_request_message","id":"toolcall-x","ts":"t",
                 "agent_id":"a","conversation_id":"c","turn_id":"T","run_id":"R",
                 "tool_call":{"tool_call_id":"tc-1","name":"Bash","arguments":"{}"}}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.ToolCallMessage>()
            parsed.toolCall?.toolCallId shouldBe "tc-1"
            parsed.toolCall?.name shouldBe "Bash"
        }

        "letta-mobile-51xm.2 a2ui_frame — routes typed A2UI messages from `a2ui` payload (§2.2)" {
            val payload = """
                {"v":1,"type":"a2ui_frame","id":"a2ui-1","ts":"t",
                 "agent_id":"a","conversation_id":"c","turn_id":"T","run_id":"R",
                 "otid":"cm-android-x","ok":true,
                 "a2ui":{"version":"v0.9","createSurface":{"surfaceId":"approval-1","catalogId":"com.letta.mobile:tool-approval/v1"}}}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.A2ui>()
            parsed.runId shouldBe "R"
            parsed.otid shouldBe "cm-android-x"
            parsed.ok shouldBe true
            parsed.messages.single().shouldBeInstanceOf<A2uiMessage.CreateSurface>()
            parsed.messages.single().surfaceId shouldBe "approval-1"
        }

        "spec §2.2 a2ui_frame — ok=false surfaces parse_error without payload" {
            val payload = """
                {"v":1,"type":"a2ui_frame","id":"a2ui-bad","ts":"t",
                 "turn_id":"T","run_id":"R","ok":false,
                 "parse_error":"Unexpected token } in JSON at position 42"}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.A2ui>()
            parsed.ok shouldBe false
            parsed.parseError shouldBe "Unexpected token } in JSON at position 42"
            parsed.messages shouldBe emptyList()
        }

        "spec §2 forward-compat — unknown type lands as ServerFrame.Unknown" {
            val payload = """
                {"v":1,"type":"some_future_type","id":"f","ts":"t","extra":"value"}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.Unknown>()
            parsed.type shouldBe "some_future_type"
        }

        "letta-mobile-2rkdj subscribe_frame — exposes run_id + seq + inner frame as JsonObject" {
            val payload = """
                {"v":1,"type":"subscribe_frame","id":"env-1","ts":"t",
                 "run_id":"run-9","seq":17,
                 "frame":{"message_type":"assistant_message","content":"hello",
                          "run_id":"run-9","turn_id":"T","id":"cm-stream-x",
                          "agent_id":"a","conversation_id":"c","ts":"t","v":1,
                          "type":"assistant_message"}}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.SubscribeFrameMessage>()
            parsed.runId shouldBe "run-9"
            parsed.seq shouldBe 17L
            // Inner frame retained as JsonObject so the caller can
            // re-route it through the live handler.
            parsed.frame["message_type"]?.jsonPrimitive?.content shouldBe "assistant_message"
            parsed.frame["content"]?.jsonPrimitive?.content shouldBe "hello"
        }

        "letta-mobile-2rkdj subscribe_done — carries last_seq + terminal status" {
            val payload = """
                {"v":1,"type":"subscribe_done","id":"env-2","ts":"t",
                 "run_id":"run-9","last_seq":42,"status":"completed"}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.SubscribeDone>()
            parsed.runId shouldBe "run-9"
            parsed.lastSeq shouldBe 42L
            parsed.status shouldBe "completed"
        }
    }

    "Cron frame deserialization (letta-mobile-d52f.1, sister to lcp-d5g)" should {
        "cron_list_response — parses tasks array and request_id" {
            val payload = """
                {"v":1,"type":"cron_list_response","id":"r-1","ts":"t","request_id":"req-1",
                 "success":true,
                 "tasks":[
                   {"id":"t1","agent_id":"a","conversation_id":"c","name":"n","description":"d",
                    "cron":"*/5 * * * *","timezone":"UTC","recurring":true,"prompt":"p","status":"active",
                    "created_at":"2026-01-01T00:00:00Z","expires_at":null,"last_fired_at":null,
                    "fire_count":0,"cancel_reason":null,"jitter_offset_ms":0,
                    "scheduled_for":null,"fired_at":null,"missed_at":null}
                 ]}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.CronListResponse>()
            parsed.requestId shouldBe "req-1"
            parsed.success shouldBe true
            parsed.tasks.size shouldBe 1
            parsed.tasks.first().id shouldBe "t1"
            parsed.tasks.first().agentId shouldBe "a"
            parsed.tasks.first().cron shouldBe "*/5 * * * *"
            parsed.tasks.first().recurring shouldBe true
        }

        "cron_add_response — carries task and warning when success" {
            val payload = """
                {"v":1,"type":"cron_add_response","id":"r","ts":"t","request_id":"r1",
                 "success":true,"warning":"resolved every→cron",
                 "task":{"id":"t1","agent_id":"a","conversation_id":"c","name":"n","description":"d",
                   "cron":"0 9 * * *","timezone":"UTC","recurring":true,"prompt":"p","status":"active",
                   "created_at":"2026-01-01T00:00:00Z","fire_count":0,"jitter_offset_ms":0}}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.CronAddResponse>()
            parsed.success shouldBe true
            parsed.task?.id shouldBe "t1"
            parsed.warning shouldBe "resolved every→cron"
            parsed.error shouldBe null
        }

        "cron_add_response — surfaces error on failure" {
            val payload = """
                {"v":1,"type":"cron_add_response","id":"r","ts":"t","request_id":"r1",
                 "success":false,"error":"invalid cron expression"}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.CronAddResponse>()
            parsed.success shouldBe false
            parsed.error shouldBe "invalid cron expression"
            parsed.task shouldBe null
        }

        "cron_delete_response / cron_delete_all_response — minimal shapes" {
            val del = json.decodeFromString(
                ServerFrameSerializer,
                """{"v":1,"type":"cron_delete_response","id":"r","ts":"t","request_id":"r","success":true}""",
            )
            del.shouldBeInstanceOf<ServerFrame.CronDeleteResponse>()
            del.success shouldBe true

            val all = json.decodeFromString(
                ServerFrameSerializer,
                """{"v":1,"type":"cron_delete_all_response","id":"r","ts":"t","request_id":"r","success":true,"count":3}""",
            )
            all.shouldBeInstanceOf<ServerFrame.CronDeleteAllResponse>()
            all.count shouldBe 3L
        }

        "crons_updated — parses push event with reason and active count" {
            val payload = """
                {"v":1,"type":"crons_updated","id":"u-1","ts":"2026-05-19T00:00:00Z",
                 "reason":"client_mutation","tasks_active":2,"at":"2026-05-19T00:00:00Z"}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.CronsUpdated>()
            parsed.reason shouldBe "client_mutation"
            parsed.tasksActive shouldBe 2L
        }
    }

    "Subagent frame deserialization (letta-mobile-73o2h.3, §13)" should {
        "subagent_list_response — parses subagents array and correlation id (§13.2)" {
            val payload = """
                {"v":1,"type":"subagent_list_response","id":"r-1","ts":"t","request_id":"r1",
                 "success":true,
                 "subagents":[
                   {"toolCallId":"toolu_1","description":"do the thing",
                    "subagentType":"general-purpose","status":"running",
                    "taskId":"task_2","subagentAgentId":"agent-local-x",
                    "parentRunId":"run-9","startedAt":"2026-06-01T00:00:00Z"}
                 ]}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.SubagentListResponse>()
            parsed.requestId shouldBe "r1"
            parsed.success shouldBe true
            parsed.subagents.size shouldBe 1
            val first = parsed.subagents.first()
            first.toolCallId shouldBe "toolu_1"
            first.subagentType shouldBe "general-purpose"
            first.status shouldBe "running"
            first.taskId shouldBe "task_2"
            first.subagentAgentId shouldBe "agent-local-x"
            first.parentRunId shouldBe "run-9"
        }

        "subagent_list_response — surfaces error on failure" {
            val payload = """
                {"v":1,"type":"subagent_list_response","id":"r","ts":"t","request_id":"r1",
                 "success":false,"error":"registry unavailable"}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.SubagentListResponse>()
            parsed.success shouldBe false
            parsed.error shouldBe "registry unavailable"
            parsed.subagents shouldBe emptyList()
        }

        "subagent_todos_response — parses todos snapshot + lifecycle (§13.3)" {
            val payload = """
                {"v":1,"type":"subagent_todos_response","id":"r","ts":"t","request_id":"r2",
                 "success":true,"found":true,"todos_found":true,
                 "subagent":{"toolCallId":"toolu_1","description":"d","subagentType":"general-purpose","status":"running"},
                 "todos":[
                   {"content":"step one","status":"completed","activeForm":"Doing step one"},
                   {"content":"step two","status":"in_progress","activeForm":"Doing step two"}
                 ]}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.SubagentTodosResponse>()
            parsed.success shouldBe true
            parsed.found shouldBe true
            parsed.todosFound shouldBe true
            parsed.subagent?.toolCallId shouldBe "toolu_1"
            parsed.todos.size shouldBe 2
            parsed.todos.first().content shouldBe "step one"
            parsed.todos.first().status shouldBe "completed"
            parsed.todos[1].status shouldBe "in_progress"
        }

        "subagent_todos_response — degrades gracefully when not found" {
            val payload = """
                {"v":1,"type":"subagent_todos_response","id":"r","ts":"t","request_id":"r2",
                 "success":true,"found":false,"todos_found":false}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.SubagentTodosResponse>()
            parsed.found shouldBe false
            parsed.todosFound shouldBe false
            parsed.subagent shouldBe null
            parsed.todos shouldBe emptyList()
        }

        "subagents_updated — parses push with reason + fresh active snapshot (§13.4)" {
            val payload = """
                {"v":1,"type":"subagents_updated","id":"u-1","ts":"2026-06-01T00:00:00Z",
                 "reason":"started",
                 "subagent":{"toolCallId":"toolu_1","description":"d","subagentType":"general-purpose","status":"running"},
                 "subagents_active":[
                   {"toolCallId":"toolu_1","description":"d","subagentType":"general-purpose","status":"running"}
                 ],
                 "at":"2026-06-01T00:00:00Z"}
            """.trimIndent()
            val parsed = json.decodeFromString(ServerFrameSerializer, payload)
            parsed.shouldBeInstanceOf<ServerFrame.SubagentsUpdated>()
            parsed.reason shouldBe "started"
            parsed.subagent?.toolCallId shouldBe "toolu_1"
            parsed.subagentsActive.size shouldBe 1
            parsed.subagentsActive.first().status shouldBe "running"
        }
    }
})
