package com.letta.mobile.data.runtime

import com.letta.mobile.util.textOrNull

import com.letta.mobile.util.booleanOrNull

import app.cash.turbine.test
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInputPayload
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put


@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineOwnershipTest : AppServerTurnEngineFixture() {
    @Test
    fun runTurnPreservesMultimodalContentParts() = runTest {
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(client = client)
        val contentParts = JsonArray(
            listOf(
                buildJsonObject {
                    put("type", "text")
                    put("text", "look")
                },
                buildJsonObject {
                    put("type", "image")
                    put("source", buildJsonObject {
                        put("type", "base64")
                        put("media_type", "image/png")
                        put("data", "abc123")
                    })
                },
            ),
        )

        engine.runTurn(
            command.copy(input = TurnInput.UserMessage("local-image", "look", contentPartsJson = contentParts.toString())),
        ).test {
            awaitItem()
            val input = assertIs<AppServerCommand.Input>(client.sentCommands.single())
            val payload = assertIs<com.letta.mobile.data.transport.appserver.AppServerInputPayload.CreateMessage>(input.payload)
            val content = payload.messages.single().content.jsonArray
            assertEquals("text", content[0].jsonObject["type"]?.jsonPrimitive?.content)
            assertEquals("look", content[0].jsonObject["text"]?.jsonPrimitive?.content)
            assertEquals("image", content[1].jsonObject["type"]?.jsonPrimitive?.content)
            assertEquals("abc123", content[1].jsonObject["source"]?.jsonObject?.get("data")?.jsonPrimitive?.content)

            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
            assertEquals("stop_reason", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)
            awaitItem()
            awaitComplete()
        }
    }

    @Test
    fun activeTurnOwnerIsNullWhenIdle() = runTest {
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(client = client)

        // letta-mobile-kyqdt: no turn has run, so no owner metadata exists.
        assertNull(engine.activeTurnOwner)
    }

    @Test
    fun activeTurnOwnerIsPopulatedWhileActiveAndClearedOnCompletion() = runTest {
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(client = client)

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertIs<AppServerCommand.Input>(client.sentCommands.single())

            // letta-mobile-kyqdt: while the turn holds the lock, owner metadata
            // identifies the acquiring run/agent/conversation. Presence + values
            // only — no timing/behavior assertions.
            val owner = engine.activeTurnOwner
            assertNotNull(owner)
            assertEquals("runtime-1", owner.runtimeId)
            assertEquals("agent-1", owner.agentId)
            assertEquals("conv-1", owner.conversationId)

            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
            assertEquals("stop_reason", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)
            val completed = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Completed, completed.status)
            awaitComplete()
        }

        // letta-mobile-kyqdt: the finally block clears owner metadata when the
        // lock is released, so the engine reports idle ownership again.
        assertNull(engine.activeTurnOwner)
    }

    @Test
    fun activeTurnOwnerRunIdIsPromotedWhenStreamFramesRevealServerRunId() = runTest {
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(client = client)

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertIs<AppServerCommand.Input>(client.sentCommands.single())

            // letta-mobile-kyqdt P1b: owner.runId is null until a server frame
            // reveals the run id.
            assertNull(engine.activeTurnOwner?.runId)

            // A streamed frame carries the server-assigned run id; the engine
            // promotes it into the owner metadata (pure copy).
            client.emit(streamDelta(messageType = "assistant_message", runId = "run-8c7b6ac1"))
            assertEquals("assistant_message", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)

            val owner = engine.activeTurnOwner
            assertNotNull(owner)
            assertEquals("run-8c7b6ac1", owner.runId)

            client.emit(streamDelta(messageType = "stop_reason", runId = "run-8c7b6ac1"))
            assertEquals("stop_reason", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)
            val completed = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Completed, completed.status)
            awaitComplete()
        }
    }

    @Test
    fun activeTurnOwnerRecordsProcessRoleAndDeadlinesWhileActive() = runTest {
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(
            client = client,
            permissionMode = AppServerPermissionMode.Unrestricted,
        )

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertIs<AppServerCommand.Input>(client.sentCommands.single())

            // letta-mobile-kyqdt P1c/P2: the owner captures the process/permission
            // role plus the settle + watchdog deadlines in force for this turn.
            val owner = engine.activeTurnOwner
            assertNotNull(owner)
            assertEquals(AppServerPermissionMode.Unrestricted.name, owner.processRole)
            assertNotNull(owner.settleDeadlineMs)
            assertNotNull(owner.watchdogDeadlineMs)

            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
            assertEquals("stop_reason", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            awaitComplete()
        }
    }

    @Test
    fun releasedTelemetryRecordsTerminalSourceScopeMatchAndReleaseReasonOnCompletion() = runTest {
        com.letta.mobile.util.Telemetry.clear()
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(client = client)

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertIs<AppServerCommand.Input>(client.sentCommands.single())

            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
            assertEquals("stop_reason", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)
            val completed = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Completed, completed.status)
            awaitComplete()
        }

        // letta-mobile-kyqdt P1c: the released telemetry event carries the
        // terminal diagnostics (source, scope-match) plus the release reason.
        // Presence/value assertions only — no timing assertions.
        val released = com.letta.mobile.util.Telemetry.snapshot().first {
            it.tag == "AppServerTurnEngine" && it.name == "activeTurn.released"
        }
        assertEquals(RuntimeRunStatus.Completed.name, released.attrs["lastTerminal"]?.textOrNull)
        assertEquals(true, released.attrs["lastTerminalScopeMatched"]?.booleanOrNull)
        assertNotNull(released.attrs["lastTerminalSource"]?.textOrNull)
        assertEquals("normal_completion", released.attrs["releaseReason"]?.textOrNull)
    }

    @Test
    fun releasedTelemetryKeepsWatchdogTimeoutAsReleaseReason() = runTest {
        // letta-mobile-qygvv.1 regression: the input-acceptance refactor made the
        // post-join step return "normal_completion", which overwrote the reason
        // the collector had already recorded. A watchdog release must still say
        // so, otherwise the orphan-abort path (qygvv.3) never sees it.
        com.letta.mobile.util.Telemetry.clear()
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(
            client = client,
            turnIdleTimeoutMs = 300,
            nowMs = { testScheduler.currentTime },
        )
        val payloads = MutableStateFlow<List<RuntimeEventPayload>>(emptyList())
        val job = launch {
            engine.runTurn(command).collect { draft -> payloads.update { it + draft.payload } }
        }
        runCurrent()
        assertTrue(client.sentCommands.any { it is AppServerCommand.Input })

        advanceTimeBy(300L * 4)
        runCurrent()
        job.join()

        val failed = payloads.value.filterIsInstance<RuntimeEventPayload.RunLifecycleChanged>().last()
        assertEquals(RuntimeRunStatus.Failed, failed.status)
        val released = com.letta.mobile.util.Telemetry.snapshot().first {
            it.tag == "AppServerTurnEngine" && it.name == "activeTurn.released"
        }
        assertEquals("watchdog_timeout", released.attrs["releaseReason"]?.textOrNull)
    }

    @Test
    fun supersededRunIdFramesAreDroppedAfterMidTurnReassignment() = runTest {
        // lgns8.22.4: after the lease promotes from run-1 → run-2, a late
        // run-1 terminal must not complete the turn.
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(client = client)

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)

            client.emit(streamDelta(messageType = "assistant_message", runId = "run-1"))
            assertEquals("assistant_message", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)

            client.emit(streamDelta(messageType = "assistant_message", runId = "run-2"))
            assertEquals("assistant_message", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)

            // Stale terminal for superseded run-1 — must be ignored.
            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
            runCurrent()
            expectNoEvents()

            client.emit(streamDelta(messageType = "stop_reason", runId = "run-2"))
            assertEquals("stop_reason", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)
            val completed = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Completed, completed.status)
            awaitComplete()
        }
    }

    @Test
    fun authoritativeSameConversationErrorMessageRecordsFailedNotCompleted() = runTest {
        // lgns8.22.4: same-conversation scope-mismatched error_message must
        // record Failed on the owner, never Completed.
        com.letta.mobile.util.Telemetry.clear()
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(client = client)
        val wrongAgentSameConv = AppServerRuntimeScope("agent-OTHER", "conv-1")

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            client.emit(
                streamDelta(
                    messageType = "error_message",
                    runId = "run-1",
                    runtime = wrongAgentSameConv,
                ),
            )
            val terminal = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Failed, terminal.status)
            awaitComplete()
        }

        val released = com.letta.mobile.util.Telemetry.snapshot().first {
            it.tag == "AppServerTurnEngine" && it.name == "activeTurn.released"
        }
        assertEquals(RuntimeRunStatus.Failed.name, released.attrs["lastTerminal"]?.textOrNull)
        assertEquals(false, released.attrs["lastTerminalScopeMatched"]?.booleanOrNull)
        assertEquals("authoritative_terminal_scope_mismatched", released.attrs["lastTerminalSource"]?.textOrNull)
    }

    @Test
    fun activeTurnOwnerRecordsScopeRejectedTerminalWhenTerminalFailsScopeMatch() = runTest {
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(client = client)

        val otherRuntime = AppServerRuntimeScope("agent-OTHER", "conv-OTHER")
        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertIs<AppServerCommand.Input>(client.sentCommands.single())

            // A terminal-bearing frame arrives scoped to a DIFFERENT runtime, so
            // matches(scope) rejects it. The owner records the rejected scope
            // decision (the leading hypothesis: terminal arrived but failed
            // matches(scope)). This is pure logging: the turn is NOT terminated.
            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1", runtime = otherRuntime))
            runCurrent()
            expectNoEvents()

            val owner = engine.activeTurnOwner
            assertNotNull(owner)
            assertEquals(false, owner.lastTerminalScopeMatched)
            assertEquals("scope_rejected_terminal", owner.lastTerminalSource)

            // A correctly-scoped terminal still completes the turn normally
            // (proving the rejected-terminal record did not change behavior).
            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
            assertEquals("stop_reason", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)
            val completed = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Completed, completed.status)
            awaitComplete()
        }
    }

    @Test
    fun activeTurnOwnerIsNonNullWheneverEngineIsBusy() = runTest {
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(client = client)

        val first = backgroundScope.async {
            engine.runTurn(command).collect()
        }
        runCurrent()

        // letta-mobile-kyqdt P1a: whenever the engine reports busy, the owner
        // metadata MUST be present — there is no "busy but unknown owner" window.
        // (The finally clears the owner strictly AFTER unlock, so busy implies a
        // non-null owner.)
        if (engine.isAnyBusy) {
            assertNotNull(engine.activeTurnOwner)
        }

        client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
        first.await()

        // After release, idle again and owner is cleared.
        assertNull(engine.activeTurnOwner)
    }

    @Test
    fun runTurnRejectsConcurrentTurnsForSameRuntimeKey() = runTest {
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(client = client)

        val first = backgroundScope.async {
            engine.runTurn(command).collect()
        }
        runCurrent()

        assertFailsWith<IllegalStateException> {
            engine.runTurn(command.copy(input = TurnInput.UserMessage("local-2", "second"))).take(1).collect()
        }

        client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
        first.await()
    }

    @Test
    fun runTurnStartsNewRuntimeWhenAgentOrConversationChanges() = runTest {
        var requestId = 0
        val client = FakeAppServerClient()
        val engine = AppServerTurnEngine(
            client = client,
            requestIdFactory = {
                requestId += 1
                "runtime-start-$requestId"
            },
        )

        engine.runTurn(command).test {
            awaitItem()
            client.emit(streamDelta(messageType = "stop_reason", runId = "run-1"))
            assertEquals("stop_reason", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)
            awaitItem()
            awaitComplete()
        }

        val secondRuntime = AppServerRuntimeScope("agent-2", "conv-2")
        val secondCommand = command.copy(
            agentId = AgentId(secondRuntime.agentId),
            conversationId = ConversationId(secondRuntime.conversationId),
            input = TurnInput.UserMessage("local-2", "second"),
        )

        engine.runTurn(secondCommand).test {
            awaitItem()
            val input = assertIs<AppServerCommand.Input>(client.sentCommands.last())
            assertEquals(secondRuntime, input.runtime)
            client.emit(streamDelta(messageType = "stop_reason", runId = "run-2", runtime = secondRuntime))
            assertEquals("stop_reason", assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType)
            awaitItem()
            awaitComplete()
        }

        assertEquals(
            listOf("agent-1", "agent-2"),
            client.runtimeStartCommands.map { it.agentId },
        )
        // letta-mobile-qygvv.1: each user-message input also draws a request_id
        // (for input_accepted), so the second runtime_start gets the third id.
        assertEquals(
            listOf("runtime-start-1", "runtime-start-3"),
            client.runtimeStartCommands.map { it.requestId },
        )
    }
}

