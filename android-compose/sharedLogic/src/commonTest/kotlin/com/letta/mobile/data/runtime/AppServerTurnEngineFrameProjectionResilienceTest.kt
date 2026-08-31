package com.letta.mobile.data.runtime

import app.cash.turbine.test
import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.transport.appserver.AppServerChannel
import com.letta.mobile.data.transport.appserver.AppServerClient
import com.letta.mobile.data.transport.appserver.AppServerCommand
import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.BackendId
import com.letta.mobile.runtime.ConversationId
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeId
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.TurnCommand
import com.letta.mobile.runtime.TurnInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * letta-mobile-gdvbf: a frame the engine cannot PROJECT must not decide the
 * terminal of the turn.
 *
 * Observed on device: a subagent completed, the assistant streamed its
 * acknowledgement, and the parent turn was still reported as
 * "This turn failed before the assistant could reply." Any exception out of
 * `processReceivedFrame` escaped the collect loop into the blanket
 * `catch (e: Throwable)`, which settles the WHOLE turn with
 * `turnEndReason = "Tool execution interrupted by stream error"`. One
 * unprojectable field therefore outranked every piece of successful evidence
 * in the turn.
 *
 * Terminal authority belongs to lifecycle frames. There are now two defences
 * and these tests cover both:
 *
 *  - the MAPPER no longer throws on a delta it cannot read (a `stream_delta`
 *    whose `delta` is a JSON ARRAY used to hit `.jsonObject`); such a frame is
 *    surfaced as an `ExternalTransportFrame` draft, the same treatment Unknown
 *    and DecodeFailure frames get, so it stays observable;
 *  - the ENGINE tolerates a projection failure it did not anticipate, because
 *    the mapper cannot be expected to foresee every future wire shape.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppServerTurnEngineFrameProjectionResilienceTest {

    @Test
    fun anUnprojectableFrameDoesNotFailTheTurn() = runTest {
        val client = ProjectionFailureClient()
        val engine = AppServerTurnEngine(client = client, requestIdFactory = { "runtime-start-1" })

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)

            // The frame that used to kill the turn.
            client.emitUnprojectableDelta()
            // The real terminal of the turn, arriving after it.
            client.emitStopReason()

            // It is preserved as an observable external-transport draft rather
            // than being swallowed — we must not lose sight of a frame we could
            // not read, only stop letting it decide the terminal.
            assertIs<RuntimeEventPayload.ExternalTransportFrame>(awaitItem().payload)
            assertEquals(
                "stop_reason",
                assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType,
            )
            val terminal = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(
                RuntimeRunStatus.Completed,
                terminal.status,
                "a projection failure must not outrank the real terminal of the turn",
            )
            awaitComplete()
        }
    }

    @Test
    fun successfulFramesAroundAnUnprojectableOneStillProject() = runTest {
        val client = ProjectionFailureClient()
        val engine = AppServerTurnEngine(client = client, requestIdFactory = { "runtime-start-1" })

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)

            client.emitAssistantText("Dispatched a mundane read-only inventory task.")
            assertEquals(
                "assistant_message",
                assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType,
            )

            client.emitUnprojectableDelta()
            client.emitAssistantText("Task: task_9")
            assertIs<RuntimeEventPayload.ExternalTransportFrame>(awaitItem().payload)
            assertEquals(
                "assistant_message",
                assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType,
                "a frame AFTER the unprojectable one must still be delivered",
            )

            client.emitStopReason()
            assertEquals(
                "stop_reason",
                assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType,
            )
            val terminal = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Completed, terminal.status)
            awaitComplete()
        }
    }

    /**
     * The two tests above cover the defence in the mapper. This one covers the
     * defence in the ENGINE, independently of any particular data vector: a
     * mapper that throws on one frame must not decide the terminal either.
     * Both layers matter — the mapper cannot anticipate every future field, and
     * the precedence rule is what makes an unanticipated one survivable.
     */
    @Test
    fun aThrowingMapperOnOneFrameDoesNotFailTheTurn() = runTest {
        val client = ProjectionFailureClient()
        val mapper = ThrowOnNthFrameMapper(throwOnFrame = 1)
        val engine = AppServerTurnEngine(
            client = client,
            mapper = mapper,
            requestIdFactory = { "runtime-start-1" },
        )

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)

            client.emitAssistantText("this one blows up in the mapper")
            client.emitStopReason()

            assertEquals(
                "stop_reason",
                assertIs<RuntimeEventPayload.RemoteStreamFrame>(awaitItem().payload).messageType,
            )
            val terminal = assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)
            assertEquals(RuntimeRunStatus.Completed, terminal.status)
            awaitComplete()
        }
    }

    @Test
    fun aSustainedRunOfProjectionFailuresStillFailsTheTurn() = runTest {
        val client = ProjectionFailureClient()
        val mapper = ThrowOnNthFrameMapper(throwOnFrame = null) // throw on every frame
        val engine = AppServerTurnEngine(
            client = client,
            mapper = mapper,
            requestIdFactory = { "runtime-start-1" },
        )

        engine.runTurn(command).test {
            assertIs<RuntimeEventPayload.RunLifecycleChanged>(awaitItem().payload)

            // Tolerating one bad frame is resilience; tolerating an endlessly
            // broken stream would be pretending it is fine. Past the budget the
            // error is rethrown and the turn settles exactly as it did before
            // this change — the failure propagates out of the flow.
            repeat(BUDGET_OVERRUN) { client.emitAssistantText("boom") }

            assertIs<IllegalStateException>(awaitError())
        }
    }

    /**
     * Throws while projecting the [throwOnFrame]-th frame (1-based), or every
     * frame when null. Delegates everything else to the real mapper so the rest
     * of the turn behaves normally.
     */
    private class ThrowOnNthFrameMapper(private val throwOnFrame: Int?) : AppServerRuntimeEventMapper() {
        private val real = AppServerRuntimeEventMapper()
        private var seen = 0

        override fun map(command: TurnCommand, received: AppServerReceivedFrame): List<RuntimeEventDraft> {
            seen += 1
            if (throwOnFrame == null || seen == throwOnFrame) {
                error("simulated projection failure on frame $seen")
            }
            return real.map(command, received)
        }
    }

    private companion object {
        /** One more than MAX_CONSECUTIVE in AppServerTurnEngine. */
        const val BUDGET_OVERRUN = 9

        val command = TurnCommand(
            backendId = BackendId("iroh-app-server"),
            runtimeId = RuntimeId("iroh:test"),
            agentId = AgentId("agent-1"),
            conversationId = ConversationId("conv-1"),
            input = TurnInput.UserMessage(localMessageId = "local-1", text = "hi"),
        )
        val runtime = AppServerRuntimeScope("agent-1", "conv-1")
    }

    private class ProjectionFailureClient : AppServerClient {
        override val events: Flow<AppServerReceivedFrame> = MutableSharedFlow(extraBufferCapacity = 64)
        private var seq = 1L

        override suspend fun runtimeStart(
            command: AppServerCommand.RuntimeStart,
        ): AppServerInboundFrame.RuntimeStartResponse =
            AppServerInboundFrame.RuntimeStartResponse(
                requestId = command.requestId,
                success = true,
                runtime = AppServerRuntimeScope(
                    agentId = requireNotNull(command.agentId),
                    conversationId = requireNotNull(command.conversationId),
                ),
            )

        override suspend fun input(command: AppServerCommand.Input) = Unit

        override suspend fun sync(command: AppServerCommand.Sync): AppServerInboundFrame.SyncResponse =
            error("sync unused")

        override suspend fun abort(
            command: AppServerCommand.AbortMessage,
        ): AppServerInboundFrame.AbortMessageResponse = error("abort unused")

        override suspend fun adminRpc(
            command: AppServerCommand.AdminRpc,
        ): AppServerInboundFrame.AdminRpcResponse = error("adminRpc unused")

        override suspend fun sendExternalToolResponse(command: AppServerCommand.ExternalToolCallResponse) = Unit

        /**
         * A `stream_delta` whose `delta` is a JSON ARRAY. `toStreamDeltaDraft`
         * reads it with the throwing `.jsonObject` accessor, so this is a real
         * projection failure rather than a mocked one.
         */
        fun emitUnprojectableDelta() = emit(
            AppServerInboundFrame.StreamDelta(
                runtime = runtime,
                eventSeq = seq++,
                emittedAt = "2026-08-31T17:51:00Z",
                idempotencyKey = "evt-bad-" + seq,
                delta = buildJsonArray { },
            ),
        )

        fun emitAssistantText(text: String) = emit(
            AppServerInboundFrame.StreamDelta(
                runtime = runtime,
                eventSeq = seq++,
                emittedAt = "2026-08-31T17:51:01Z",
                idempotencyKey = "evt-text-" + seq,
                delta = buildJsonObject {
                    put("message_type", "assistant_message")
                    put("run_id", "run-1")
                    put("content", text)
                },
            ),
        )

        fun emitStopReason() = emit(
            AppServerInboundFrame.StreamDelta(
                runtime = runtime,
                eventSeq = seq++,
                emittedAt = "2026-08-31T17:51:02Z",
                idempotencyKey = "evt-stop",
                delta = buildJsonObject {
                    put("message_type", "stop_reason")
                    put("run_id", "run-1")
                    put("stop_reason", "end_turn")
                },
            ),
        )

        private fun emit(frame: AppServerInboundFrame.StreamDelta) {
            (events as MutableSharedFlow<AppServerReceivedFrame>).tryEmit(
                AppServerReceivedFrame(
                    channel = AppServerChannel.Stream,
                    frame = frame,
                    raw = buildJsonObject {
                        put("type", "stream_delta")
                        put("idempotency_key", frame.idempotencyKey)
                    },
                ),
            )
        }
    }
}
