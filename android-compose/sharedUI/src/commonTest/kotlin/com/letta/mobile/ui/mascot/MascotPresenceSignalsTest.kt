package com.letta.mobile.ui.mascot

import com.letta.mobile.avatar.core.AvatarExpression
import com.letta.mobile.avatar.core.AvatarGesture
import com.letta.mobile.avatar.core.AvatarLookTarget
import com.letta.mobile.avatar.core.AvatarModel
import com.letta.mobile.avatar.core.AvatarReaction
import com.letta.mobile.avatar.core.AvatarRuntime
import com.letta.mobile.avatar.core.AvatarRuntimeState
import com.letta.mobile.avatar.core.AvatarState
import com.letta.mobile.avatar.core.GazePose
import com.letta.mobile.avatar.core.GazeTarget
import com.letta.mobile.avatar.core.GazeWorld
import com.letta.mobile.avatar.core.HeadlessAvatarRuntime
import com.letta.mobile.avatar.core.MascotDirector
import com.letta.mobile.avatar.core.MascotEvent
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.core.MascotShape
import com.letta.mobile.data.presence.AgentActivityKind
import com.letta.mobile.data.presence.AgentPresence
import com.letta.mobile.data.presence.ConversationRunPhasePublisher
import com.letta.mobile.data.presence.ConversationRunRegistry
import com.letta.mobile.data.presence.RunEdges
import com.letta.mobile.data.presence.RunScope
import com.letta.mobile.data.presence.presenceByAgent
import com.letta.mobile.runtime.RuntimeEventPayload
import com.letta.mobile.runtime.RuntimeRunStatus
import com.letta.mobile.runtime.ToolApprovalId
import com.letta.mobile.runtime.ToolApprovalRequest
import com.letta.mobile.runtime.ToolCallId
import com.letta.mobile.runtime.ToolExecutionStatus
import com.letta.mobile.runtime.ToolName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-te3n1.1: presence becomes the director's signals and moments - from synthetic run
 * registry sequences shaped the way each platform publishes them - and nothing else talks to the
 * director about presence.
 */
class MascotPresenceSignalsTest {
    /**
     * Records the reactions and nothing else. Never loaded: this module's common tests also run on
     * wasm, which has no coroutine test runner, and the director drives a runtime whatever its state.
     */
    private class RecordingRuntime : AvatarRuntime {
        val reactions = mutableListOf<AvatarReaction>()
        override val state: StateFlow<AvatarRuntimeState> = MutableStateFlow(AvatarRuntimeState.Idle)

        override fun react(reaction: AvatarReaction, arg: Float) {
            reactions += reaction
        }

        override suspend fun load(model: AvatarModel) = Unit
        override suspend fun unload() = Unit
        override fun setExpression(expression: AvatarExpression, weight: Float) = Unit
        override fun setMouthOpen(value: Float) = Unit
        override fun setLookTarget(target: AvatarLookTarget?) = Unit
        override fun playGesture(gesture: AvatarGesture, fadeSeconds: Float) = Unit
        override fun playAnimation(animationId: String, loop: Boolean) = Unit
        override fun setAccessoryEnabled(accessoryId: String, enabled: Boolean) = Unit
        override fun update(deltaSeconds: Float) = Unit
        override fun dispose() = Unit
    }

    private class Rig(val runtime: RecordingRuntime) {
        val director = MascotDirector(runtime, Random(5))
        val entered = mutableListOf<AvatarState>()
        private val feed = MascotPresenceSignals()
        val registry = ConversationRunRegistry()
        private var now = 0L
        val publisher = ConversationRunPhasePublisher(registry) { ++now }

        init {
            director.addStateListener { _, enter -> entered += enter }
            feed.apply(director, AgentPresence.IDLE)
        }

        fun present(presence: AgentPresence) = feed.apply(director, presence)

        /** Publish [events] the way a platform does, then hand the shell's sample to the director. */
        fun publish(vararg events: RuntimeEventPayload, conversation: String = "c1") {
            events.forEach { publisher.onEvent(RunScope(conversation, AGENT), it) }
            sample()
        }

        fun sample() = present(registry.runs.value.presenceByAgent()[AGENT] ?: AgentPresence.IDLE)
    }

    private fun rig(): Rig = Rig(RecordingRuntime())

    private fun send(id: String) = RuntimeEventPayload.LocalUserAppend(id, "hi")
    private fun toolCall(id: String) = RuntimeEventPayload.ToolCallObserved(ToolCallId(id), ToolName("grep"))
    private fun toolReturn(id: String) = RuntimeEventPayload.ToolReturnObserved(ToolCallId(id), ToolExecutionStatus.Succeeded, "ok")
    private fun lifecycle(status: RuntimeRunStatus) = RuntimeEventPayload.RunLifecycleChanged(status)
    private fun assistant() = RuntimeEventPayload.RemoteStreamFrame(frameId = "f", messageType = "assistant_message", body = "")

    @Test
    fun anAndroidShapedTurnSendsWorksSpeaksAndSucceeds() {
        // Android folds each event batch and the shell samples the registry after it.
        val rig = rig()
        rig.publish(send("l1"), lifecycle(RuntimeRunStatus.Started))
        rig.publish(toolCall("t1"))
        rig.publish(toolReturn("t1"))
        rig.director.tick(2f) // past the working dwell
        rig.publish(assistant())
        rig.publish(lifecycle(RuntimeRunStatus.Completed))
        assertEquals(
            listOf(AvatarReaction.SENT, AvatarReaction.TOOL_START, AvatarReaction.TOOL_DONE, AvatarReaction.RUN_DONE),
            rig.runtime.reactions,
        )
        assertEquals(
            listOf(
                AvatarState.THINKING,
                AvatarState.WORKING,
                AvatarState.THINKING,
                AvatarState.SPEAKING,
                AvatarState.IDLE,
                AvatarState.SUCCESS,
            ),
            rig.entered,
        )
    }

    @Test
    fun aDesktopShapedConflatedSampleStillSeesEveryFinish() {
        // Desktop's shell reads a conflated state flow: two whole turns can land between samples.
        val rig = rig()
        rig.publisher.onEvent(RunScope("c1", AGENT), send("l1"))
        rig.publisher.onEvent(RunScope("c1", AGENT), lifecycle(RuntimeRunStatus.Completed))
        rig.sample()
        rig.director.tick(0.3f)
        rig.publisher.onEvent(RunScope("c1", AGENT), send("l2"))
        rig.publisher.onEvent(RunScope("c1", AGENT), lifecycle(RuntimeRunStatus.Completed))
        rig.publisher.onEvent(RunScope("c1", AGENT), send("l3"))
        rig.sample()
        val finishes = rig.runtime.reactions.count { it == AvatarReaction.RUN_DONE }
        assertEquals(2, finishes, "back-to-back finishes both play: ${rig.runtime.reactions}")
        rig.director.tick(2.1f) // the second success plays out
        assertEquals(AvatarState.THINKING, rig.director.state, "the third send is under way")
    }

    @Test
    fun anApprovalIsAskedOnceAndWaits() {
        val rig = rig()
        val request = ToolApprovalRequest(ToolApprovalId("ap-1"), ToolCallId("t1"), ToolName("Bash"), "ls")
        val ask = RuntimeEventPayload.ApprovalRequested(request)
        rig.publish(send("l1"))
        rig.publish(ask)
        rig.publish(ask)
        assertEquals(AvatarState.WAITING_INPUT, rig.director.state)
        assertEquals(1, rig.runtime.reactions.count { it == AvatarReaction.APPROVAL_ASK })
    }

    @Test
    fun aFailureFlashesOnceAndStaysThroughTyping() {
        val rig = rig()
        rig.publish(send("l1"))
        rig.publish(lifecycle(RuntimeRunStatus.Failed))
        rig.publisher.setUserTyping(RunScope("c1", AGENT), true)
        rig.sample()
        rig.publisher.setUserTyping(RunScope("c1", AGENT), false)
        rig.sample()
        assertEquals(AvatarState.ERROR, rig.director.state, "typing must not clear a failure that still stands")
        assertEquals(1, rig.runtime.reactions.count { it == AvatarReaction.RUN_FAILED })
        assertEquals(0, rig.runtime.reactions.count { it == AvatarReaction.RUN_DONE })
    }

    @Test
    fun aCancelEndsTheTurnWithoutASuccess() {
        val rig = rig()
        rig.publish(send("l1"))
        rig.publish(lifecycle(RuntimeRunStatus.Cancelled))
        assertTrue(AvatarReaction.RUN_DONE !in rig.runtime.reactions)
        assertTrue(AvatarState.SUCCESS !in rig.entered)
    }

    @Test
    fun aDreamingAgentDreamsAndATurnWakesIt() {
        val rig = rig()
        rig.present(AgentPresence(dreaming = true))
        assertEquals(AvatarState.DREAMING, rig.director.state)
        rig.present(AgentPresence(dreaming = true, activity = AgentActivityKind.THINKING, edges = RunEdges(sent = 1)))
        assertEquals(AvatarState.THINKING, rig.director.state)
        assertEquals(listOf(AvatarReaction.YAWN, AvatarReaction.WAKE, AvatarReaction.SENT), rig.runtime.reactions)
        rig.present(AgentPresence(dreaming = true, edges = RunEdges(sent = 1, cancelled = 1)))
        assertEquals(AvatarState.DREAMING, rig.director.state, "dozes again once the turn is over")
    }

    @Test
    fun theFirstPresenceIsABaselineNotAReplay() {
        val runtime = RecordingRuntime()
        val director = MascotDirector(runtime, Random(5))
        MascotPresenceSignals().apply(director, AgentPresence(edges = RunEdges(sent = 4, completed = 4)))
        assertTrue(runtime.reactions.isEmpty())
    }

    @Test
    fun aClearedConversationIsANewBaselineNotAMoment() {
        val before = AgentPresence(edges = RunEdges(sent = 3, completed = 3))
        assertEquals(emptyList(), mascotEvents(before, AgentPresence.IDLE))
        assertEquals(listOf<MascotEvent>(MascotEvent.Sent), mascotEvents(AgentPresence.IDLE, AgentPresence(edges = RunEdges(sent = 1))))
    }

    @Test
    fun platformFactsReachTheSignalsWithoutReplayingPresence() {
        val rig = rig()
        rig.present(AgentPresence(activity = AgentActivityKind.THINKING, edges = RunEdges(sent = 1)))
        val reactions = rig.runtime.reactions.size
        val feed = MascotPresenceSignals()
        feed.apply(rig.director, AgentPresence(activity = AgentActivityKind.THINKING, edges = RunEdges(sent = 1)))
        feed.setPlatform(rig.director, MascotPlatformFacts(reduceMotion = true))
        rig.director.tick(0.016f)
        assertTrue(rig.director.frame.reduceMotion)
        assertEquals(reactions, rig.runtime.reactions.size)
        assertEquals(AvatarState.THINKING, rig.director.state)
    }

    @Test
    fun entryFeedsPresenceDeltasAndTicksOncePerFrame() {
        var loads = 0
        var transitions = 0
        val entry = object : MascotEntry(HeadlessAvatarRuntime(), IDENTITY, applyState = {}) {
            override suspend fun load() { loads++ }
            override fun writeIdentity(identity: MascotIdentity) = Unit
            override fun dispose() = Unit
        }
        entry.director.addStateListener { _, _ -> transitions++ }
        entry.apply(AgentPresence(userTyping = true))
        entry.apply(AgentPresence(userTyping = true)) // no change, no second transition
        assertEquals(1, transitions)
        entry.tickTo(1_000_000_000L)
        entry.tickTo(1_000_000_000L) // same frame: idempotent
        entry.tickTo(1_016_000_000L)
        assertEquals(0, loads)
    }

    @Test
    fun registryGazeRectsStartUnavailable() {
        val registry = MascotIdentityRegistry()
        assertEquals(null, registry.cursor.value)
        assertEquals(null, registry.inputBounds.value)
        assertEquals(null, registry.timelineBounds.value)
        assertEquals(0, registry.composerKeystrokes)
        registry.noteComposerKeystroke()
        assertEquals(1, registry.composerKeystrokes)
    }

    @Test
    fun entryTicksGazeWhenThePointerIsAbsent() {
        val entry = object : MascotEntry(HeadlessAvatarRuntime(), IDENTITY, applyState = {}) {
            override suspend fun load() = Unit
            override fun writeIdentity(identity: MascotIdentity) = Unit
            override fun dispose() = Unit
        }
        entry.setGazeWorld(GazeWorld())
        entry.tickTo(1_000_000_000L)
        assertEquals(GazePose.CENTER, entry.lastGaze) // first frame is dt = 0
        entry.tickTo(1_016_000_000L)
        val pose = entry.lastGaze
        assertTrue(pose.lookTarget.x in 0f..1f && pose.lookTarget.y in 0f..1f)
        assertTrue(
            pose.target in setOf(GazeTarget.OWN, GazeTarget.AWAY, GazeTarget.USER),
            "without a pointer the plan still runs on the pointer-free targets: ${pose.target}",
        )
    }

    private companion object {
        const val AGENT = "a1"
        val IDENTITY = MascotIdentity(MascotShape.entries.first(), 0xFF00AA88.toInt())
    }
}
