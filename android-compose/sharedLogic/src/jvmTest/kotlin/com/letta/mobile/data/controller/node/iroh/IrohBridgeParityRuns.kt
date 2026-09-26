package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.controller.ApprovalSubmission
import com.letta.mobile.data.runtime.AppServerTurnEngine
import com.letta.mobile.data.runtime.RuntimePermissionDefaults
import com.letta.mobile.data.runtime.runLifecycleStatus
import com.letta.mobile.data.runtime.turnEngineTerminalStatuses
import com.letta.mobile.data.transport.appserver.AppServerApprovalResponseDecision
import com.letta.mobile.data.transport.appserver.AppServerPermissionMode
import com.letta.mobile.data.transport.appserver.AppServerRuntimeScope
import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.TurnCommand
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/*
 * letta-mobile-qygvv.14: the two runs the bridge parity gate compares, plus the user actions a
 * recorded turn needs (an approval denial, a mid-turn cancel) so both runs see the same ones.
 */

/** What the user does to a running turn; the direct run and the node's engine both get it. */
internal interface TurnDriver {
    /** Every draft the engine emits, on its collector. */
    suspend fun onDraft(engine: AppServerTurnEngine, command: TurnCommand, draft: RuntimeEventDraft) = Unit

    /** Once the recording has streamed up to the first command the server waits on. */
    suspend fun midTurn(engine: AppServerTurnEngine, command: TurnCommand) = Unit
}

/** The user does nothing: the engine's own policy answers whatever the server asks. */
internal object PassiveDriver : TurnDriver

/** The user denies every approval card the engine surfaces instead of auto-allowing. */
internal object DenyApprovalsDriver : TurnDriver {
    const val DENIAL = "<redacted: denied by user>"

    override suspend fun onDraft(engine: AppServerTurnEngine, command: TurnCommand, draft: RuntimeEventDraft) {
        val request = (draft.payload as? RuntimeEventPayload.ApprovalRequested)?.request ?: return
        engine.submitApprovalResponse(
            ApprovalSubmission(
                runtime = AppServerRuntimeScope(command.agentId.value, command.conversationId.value),
                approvalRequestId = request.approvalId.value,
                decision = AppServerApprovalResponseDecision.Deny(DENIAL),
                source = "user",
                toolName = request.toolName.value,
            ),
        )
    }
}

/** The user cancels the turn while it streams. */
internal object AbortMidTurnDriver : TurnDriver {
    override suspend fun midTurn(engine: AppServerTurnEngine, command: TurnCommand) {
        engine.abort(command.agentId.value, command.conversationId.value, runId = null)
    }
}

/** The device state and user a fixture's turn runs under. */
internal data class EngineSetup(
    val permissionMode: AppServerPermissionMode = RuntimePermissionDefaults.DEFAULT_MODE,
    val driver: TurnDriver = PassiveDriver,
)

/**
 * One recorded turn, the client message id its input carried, and how to run it. [divergences]
 * waives gate checks that fail today, each naming the bead that tracks the bug.
 */
internal data class ParityFixture(
    val resource: String,
    val clientMessageId: String,
    val setup: EngineSetup = EngineSetup(),
    val divergences: Map<GateCheck, String> = emptyMap(),
) {
    fun load(): AppServerRecording = AppServerRecording.load(resource)

    override fun toString(): String = resource
}

internal fun TestScope.parityEngine(client: RecordedAppServerClient, setup: EngineSetup) = AppServerTurnEngine(
    client = client,
    turnIdleTimeoutMs = 600_000,
    nowMs = { testScheduler.currentTime },
    permissionMode = setup.permissionMode,
)

/** Runs one turn on a fresh engine over [client], with [setup]'s user acting on it. */
internal suspend fun TestScope.runEngineOn(
    client: RecordedAppServerClient,
    command: TurnCommand,
    setup: EngineSetup = EngineSetup(),
): EngineOutcome {
    val engine = parityEngine(client, setup)
    val startedAt = testScheduler.currentTime
    val drafts = mutableListOf<RuntimeEventDraft>()
    val turn = launch {
        engine.runTurn(command).collect { draft ->
            drafts += draft
            setup.driver.onDraft(engine, command, draft)
        }
    }
    runCurrent()
    setup.driver.midTurn(engine, command)
    turn.join()
    return outcomeOf(drafts, testScheduler.currentTime - startedAt, engine.isBusy(command.agentId.value, command.conversationId.value))
}

private fun outcomeOf(drafts: List<RuntimeEventDraft>, elapsedMs: Long, busyAfter: Boolean): EngineOutcome {
    val terminal = drafts.lastOrNull { it.runLifecycleStatus() in turnEngineTerminalStatuses }
    return EngineOutcome(
        status = terminal?.runLifecycleStatus(),
        reason = (terminal?.payload as? RuntimeEventPayload.RunLifecycleChanged)?.reason,
        runId = terminal?.runId?.value,
        elapsedMs = elapsedMs,
        busyAfter = busyAfter,
    )
}

/** A client on the App Server itself: the recorded server and how the turn ended. */
internal class DirectRun(val server: RecordedAppServerClient, val outcome: EngineOutcome)

internal suspend fun TestScope.directRun(recording: AppServerRecording, fixture: ParityFixture): DirectRun {
    val server = RecordedAppServerClient(recording.ackJson, recording.frames, backgroundScope)
    return DirectRun(server, runEngineOn(server, turnCommandFor(recording.runtime, fixture.clientMessageId), fixture.setup))
}

/** A phone behind the node: what reached it, what the node told the App Server, how the phone ended. */
internal class BridgeRun(val phone: FakePhoneLink, val upstream: RecordedAppServerClient, val outcome: EngineOutcome)

/** The node relays the recording to a fake phone, whose capture then drives a second engine. */
internal suspend fun TestScope.bridgeRun(recording: AppServerRecording, fixture: ParityFixture): BridgeRun {
    val upstream = RecordedAppServerClient(recording.ackJson, recording.frames, backgroundScope)
    val nodeEngine = parityEngine(upstream, fixture.setup)
    val driver = fixture.setup.driver
    val command = turnCommandFor(recording.runtime, fixture.clientMessageId)
    val phone = FakePhoneLink(recording.runtime, fixture.clientMessageId, PHONE_REQUEST_ID, backgroundScope)
    val controller = controllerRunning(upstream.events) { turn ->
        nodeEngine.runTurn(turn).onEach { driver.onDraft(nodeEngine, turn, it) }
    }
    val relay = launch { phone.relay(controller, command) }
    runCurrent()
    driver.midTurn(nodeEngine, command)
    relay.join()
    val captured = RecordedAppServerClient(
        ackJson = phone.control.single().json.toString(),
        frames = phone.stream.map { it.json.toString() },
        scope = backgroundScope,
    )
    // The phone only watches: its user already acted through the node.
    return BridgeRun(phone, upstream, runEngineOn(captured, command, fixture.setup.copy(driver = PassiveDriver)))
}

internal const val PHONE_REQUEST_ID = "phone-req-1"
