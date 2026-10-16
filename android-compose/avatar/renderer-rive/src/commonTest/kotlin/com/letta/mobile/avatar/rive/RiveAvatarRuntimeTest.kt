package com.letta.mobile.avatar.rive

import com.letta.mobile.avatar.core.AvatarExpression
import com.letta.mobile.avatar.core.AvatarGesture
import com.letta.mobile.avatar.core.AvatarLookTarget
import com.letta.mobile.avatar.core.AvatarModel
import com.letta.mobile.avatar.core.AvatarReaction
import com.letta.mobile.avatar.core.AvatarRuntimeState
import com.letta.mobile.avatar.core.AvatarState
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RiveAvatarRuntimeTest {

    @Test
    fun everySustainedStateTheRigDeclaresHasItsOwnEnumKeyAndMomentaryOnesHaveNone() {
        val momentary = setOf(AvatarState.SUCCESS, AvatarState.DRAGGED)
        val sustained = AvatarState.entries - momentary
        val full = RiveMascotCapabilities(states = setOf(RiveMascotCapabilities.STATE_WORKING, RiveMascotCapabilities.STATE_DREAMING))
        val keys = sustained.map { checkNotNull(RiveAvatarContract.stateKey(it, full)) }

        assertEquals(sustained.size, keys.toSet().size, "two states share an enum key, so the asset cannot tell them apart")
        momentary.forEach { assertEquals(null, RiveAvatarContract.stateKey(it), "$it is a trigger/boolean, not an enum value") }
    }

    @Test
    fun aStateTheShippedRigLacksPlaysItsNearestNeighbour() {
        // letta-mobile-te3n1.1: the shipped mascot.riv has no working or dreaming state yet (te3n1.5).
        assertEquals("thinking", RiveAvatarContract.stateKey(AvatarState.WORKING))
        assertEquals("sleeping", RiveAvatarContract.stateKey(AvatarState.DREAMING))
        val withWorking = RiveMascotCapabilities(states = setOf(RiveMascotCapabilities.STATE_WORKING))
        assertEquals("working", RiveAvatarContract.stateKey(AvatarState.WORKING, withWorking))
    }

    @Test
    fun successAndErrorFlashesAreReactionsAndDraggedIsHeldUntilReleased() = runTest {
        val sink = RecordingSink()
        val runtime = RiveAvatarRuntime(sink).also { it.load(model()) }
        sink.writes.clear(); sink.fired.clear()

        runtime.applyState(AvatarState.SUCCESS)
        assertEquals(emptyList(), sink.writes, "success is a reaction; the state writes nothing")

        runtime.applyState(AvatarState.DRAGGED)
        runtime.applyState(AvatarState.IDLE)
        assertEquals(listOf(true, false), sink.draggedBooleans())

        runtime.applyState(AvatarState.ERROR)
        assertEquals(emptyList(), sink.fired, "the error flash is the RUN_FAILED reaction, not the state")
        assertEquals("error", sink.lastState())
    }

    @Test
    fun reactionsTheFilePlaysFireItsTriggersEveryTime() = runTest {
        val sink = RecordingSink()
        val runtime = RiveAvatarRuntime(sink).also { it.load(model()) }
        sink.writes.clear(); sink.fired.clear()

        runtime.react(AvatarReaction.RUN_DONE)
        runtime.react(AvatarReaction.RUN_DONE)
        runtime.react(AvatarReaction.RUN_FAILED)
        assertEquals(
            listOf(RiveAvatarContract.TRIGGER_SUCCESS, RiveAvatarContract.TRIGGER_SUCCESS, RiveAvatarContract.TRIGGER_ERROR),
            sink.fired,
            "back-to-back completions both play",
        )
    }

    @Test
    fun reactionsWithoutAnimationBlinkOrWriteNothing() = runTest {
        val sink = RecordingSink()
        val runtime = RiveAvatarRuntime(sink).also { it.load(model()) }
        sink.writes.clear(); sink.fired.clear()

        listOf(AvatarReaction.POKE, AvatarReaction.WAKE, AvatarReaction.TOOL_START).forEach(runtime::react)
        assertEquals(List(3) { RiveAvatarContract.TRIGGER_BLINK }, sink.fired)

        sink.writes.clear()
        val silent = AvatarReaction.entries - RiveMascotCapabilities.CURRENT.reactionTriggers.keys - RiveMascotCapabilities.CURRENT.blinkFallback
        silent.forEach(runtime::react)
        assertEquals(emptyList(), sink.writes, "wrote a name the shipped file does not declare: ${sink.writes}")

        runtime.setListenLevel(1f)
        runtime.setHovered(true)
        runtime.setPetting(true)
        runtime.setMotion(1f, 1f)
        runtime.setReducedMotion(true)
        assertEquals(emptyList(), sink.writes, "the shipped file has none of these inputs yet")
    }

    @Test
    fun reactionsBeforeLoadAreDropped() {
        val sink = RecordingSink()
        RiveAvatarRuntime(sink).react(AvatarReaction.RUN_DONE)
        assertTrue(sink.writes.isEmpty())
    }

    @Test
    fun loadingReportsLoadingToTheAssetBeforeItReportsReady() = runTest {
        val sink = RecordingSink()
        val runtime = RiveAvatarRuntime(sink)

        runtime.load(model())

        assertEquals(
            listOf(AvatarState.LOADING, AvatarState.IDLE).map(RiveAvatarContract::stateKey),
            sink.stateEnums(),
        )
        assertIs<AvatarRuntimeState.Ready>(runtime.state.value)
    }

    /** The contract says commands before Ready are best-effort; dropping them must be silent. */
    @Test
    fun commandsBeforeLoadAreDroppedRatherThanWritten() {
        val sink = RecordingSink()
        val runtime = RiveAvatarRuntime(sink)

        runtime.setMouthOpen(1f)
        runtime.setLookTarget(AvatarLookTarget.Screen(1f, 1f))
        runtime.playGesture(AvatarGesture(RiveAvatarRuntime.BLINK_GESTURE))

        assertTrue(sink.writes.isEmpty(), "wrote before ready: ${sink.writes}")
    }

    @Test
    fun screenGazeIsCentredOnTheViewportAndClamped() = runTest {
        val sink = RecordingSink()
        val runtime = RiveAvatarRuntime(sink).also { it.load(model()) }

        runtime.setLookTarget(AvatarLookTarget.Screen(0.5f, 0.5f))
        assertEquals(0f, sink.lastLookX())
        assertEquals(0f, sink.lastLookY())

        runtime.setLookTarget(AvatarLookTarget.Screen(0f, 1f))
        assertEquals(-1f, sink.lastLookX())
        assertEquals(1f, sink.lastLookY())

        // Null is the idle gaze, not "leave the eyes wherever they were pointed".
        runtime.setLookTarget(null)
        assertEquals(0f, sink.lastLookX())
        assertEquals(0f, sink.lastLookY())
    }

    /** World space assumes a scene a flat rig has no equivalent of, so it must not be guessed at. */
    @Test
    fun unsupportedCapabilitiesAreDroppedSilently() = runTest {
        val sink = RecordingSink()
        val runtime = RiveAvatarRuntime(sink).also { it.load(model()) }
        sink.writes.clear()

        runtime.playAnimation("wave", loop = true)
        runtime.setAccessoryEnabled("glasses", enabled = true)
        runtime.playGesture(AvatarGesture("shrug"))

        assertTrue(sink.writes.isEmpty(), "honoured a capability the mascot lacks: ${sink.writes}")
    }

    @Test
    fun mouthOpenIsClampedToTheInputRange() = runTest {
        val sink = RecordingSink()
        val runtime = RiveAvatarRuntime(sink).also { it.load(model()) }

        runtime.setMouthOpen(4f)
        assertEquals(1f, sink.lastMouth())

        runtime.setMouthOpen(-4f)
        assertEquals(0f, sink.lastMouth())
    }

    @Test
    fun anExpressionWritesNothingTheStateIsTheOnlyChannel() = runTest {
        // The director installs an expression on every state it enters; mapping those onto the
        // sustained enum fought the state it had just set (SPEAKING's Happy fired the success
        // flash, LISTENING's Neutral reset to idle). Expressions live inside the file's states.
        val sink = RecordingSink()
        val runtime = RiveAvatarRuntime(sink).also { it.load(model()) }
        val before = sink.lastState()

        runtime.setExpression(AvatarExpression.Happy)

        assertEquals(emptyList(), sink.fired)
        assertEquals(before, sink.lastState())
    }

    @Test
    fun aZeroWeightExpressionLeavesTheStateAlone() = runTest {
        val sink = RecordingSink()
        val runtime = RiveAvatarRuntime(sink).also { it.load(model()) }
        val before = sink.lastState()

        runtime.setExpression(AvatarExpression.Happy, weight = 0f)

        assertEquals(before, sink.lastState())
    }

    @Test
    fun headTurnContractNamesMatchCheckContractRegex() {
        // check_contract.py greps `const val (?:INPUT|TRIGGER)_\w+: String = "(\w+)"`.
        assertEquals("turnX", RiveAvatarContract.INPUT_TURN_X)
        assertEquals("turnY", RiveAvatarContract.INPUT_TURN_Y)
    }

    @Test
    fun setHeadTurnWritesTheFacingJoystick() = runTest {
        val sink = RecordingSink()
        val runtime = RiveAvatarRuntime(sink).also { it.load(model()) }

        runtime.setHeadTurn(1f, -1f)
        assertEquals(1f, sink.lastTurnX())
        assertEquals(-1f, sink.lastTurnY())

        runtime.setHeadTurn(4f, -4f)
        assertEquals(1f, sink.lastTurnX())
        assertEquals(-1f, sink.lastTurnY())
    }

    @Test
    fun blinkFiresTheTriggerAndNothingElseDoes() = runTest {
        val sink = RecordingSink()
        val runtime = RiveAvatarRuntime(sink).also { it.load(model()) }

        runtime.playGesture(AvatarGesture(RiveAvatarRuntime.BLINK_GESTURE))

        assertEquals(listOf(RiveAvatarContract.TRIGGER_BLINK), sink.fired)
    }

    @Test
    fun aDisposedRuntimeStopsWritingAndReportsIdle() = runTest {
        val sink = RecordingSink()
        val runtime = RiveAvatarRuntime(sink).also { it.load(model()) }

        runtime.dispose()
        sink.writes.clear()
        runtime.applyState(AvatarState.SPEAKING)
        runtime.setMouthOpen(1f)

        assertTrue(sink.writes.isEmpty(), "wrote after dispose: ${sink.writes}")
        assertIs<AvatarRuntimeState.Idle>(runtime.state.value)
    }

    private fun model() = AvatarModel(
        id = "mascot",
        displayName = "Mascot",
        uri = "asset://mascot.riv",
    )

    private class RecordingSink : RiveInputSink {
        val writes = mutableListOf<Pair<String, Any>>()
        val fired = mutableListOf<String>()

        override fun setNumber(input: String, value: Float) {
            writes += input to value
        }

        override fun setBoolean(input: String, value: Boolean) {
            writes += input to value
        }

        override fun setEnum(input: String, key: String) {
            writes += input to key
        }

        override fun setColor(input: String, argb: Int) {
            writes += input to argb
        }

        override fun fire(input: String) {
            writes += input to Unit
            fired += input
        }

        fun lastLookX(): Float? = lastNumber(RiveAvatarContract.INPUT_LOOK_X)
        fun lastLookY(): Float? = lastNumber(RiveAvatarContract.INPUT_LOOK_Y)
        fun lastTurnX(): Float? = lastNumber(RiveAvatarContract.INPUT_TURN_X)
        fun lastTurnY(): Float? = lastNumber(RiveAvatarContract.INPUT_TURN_Y)
        fun lastMouth(): Float? = lastNumber(RiveAvatarContract.INPUT_MOUTH_OPEN)
        fun lastState(): String? = lastEnum(RiveAvatarContract.INPUT_STATE)
        fun stateEnums(): List<String> = enums(RiveAvatarContract.INPUT_STATE)
        fun draggedBooleans(): List<Boolean> = booleans(RiveAvatarContract.INPUT_DRAGGED)

        private fun numbers(input: String): List<Float> =
            writes.filter { it.first == input }.map { it.second as Float }

        private fun lastNumber(input: String): Float? = numbers(input).lastOrNull()

        private fun enums(input: String): List<String> =
            writes.filter { it.first == input }.map { it.second as String }

        private fun lastEnum(input: String): String? = enums(input).lastOrNull()

        private fun booleans(input: String): List<Boolean> =
            writes.filter { it.first == input }.map { it.second as Boolean }
    }
}
