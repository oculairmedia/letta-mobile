package com.letta.mobile.avatar.rive

import com.letta.mobile.avatar.core.AvatarExpression
import com.letta.mobile.avatar.core.AvatarFormat
import com.letta.mobile.avatar.core.AvatarGesture
import com.letta.mobile.avatar.core.AvatarLookTarget
import com.letta.mobile.avatar.core.AvatarModel
import com.letta.mobile.avatar.core.AvatarRuntimeState
import com.letta.mobile.avatar.core.AvatarState
import com.letta.mobile.avatar.core.AvatarViseme
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RiveAvatarRuntimeTest {

    @Test
    fun everySustainedStateHasItsOwnEnumKeyAndMomentaryOnesHaveNone() {
        val momentary = setOf(AvatarState.SUCCESS, AvatarState.DRAGGED)
        val sustained = AvatarState.entries - momentary
        val keys = sustained.map { checkNotNull(RiveAvatarContract.stateKey(it)) }

        assertEquals(sustained.size, keys.toSet().size, "two states share an enum key, so the asset cannot tell them apart")
        momentary.forEach { assertEquals(null, RiveAvatarContract.stateKey(it), "$it is a trigger/boolean, not an enum value") }
    }

    @Test
    fun successIsAFlashTheFilePlaysAndDraggedIsHeldUntilReleased() = runTest {
        val sink = RecordingSink()
        val runtime = RiveAvatarRuntime(sink).also { it.load(model()) }
        sink.writes.clear(); sink.fired.clear()

        runtime.applyState(AvatarState.SUCCESS)
        assertEquals(listOf(RiveAvatarContract.TRIGGER_SUCCESS), sink.fired)
        assertEquals(emptyList(), sink.stateEnums(), "success must not become the sustained state")

        runtime.applyState(AvatarState.DRAGGED)
        runtime.applyState(AvatarState.IDLE)
        assertEquals(listOf(true, false), sink.draggedBooleans())

        runtime.applyState(AvatarState.ERROR)
        assertEquals(RiveAvatarContract.TRIGGER_ERROR, sink.fired.last())
        assertEquals("error", sink.lastState())
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
    fun worldGazeIsIgnoredRatherThanFlattened() = runTest {
        val sink = RecordingSink()
        val runtime = RiveAvatarRuntime(sink).also { it.load(model()) }
        sink.writes.clear()

        runtime.setLookTarget(AvatarLookTarget.World(1f, 2f, 3f))

        assertTrue(sink.writes.isEmpty(), "flattened a world gaze: ${sink.writes}")
    }

    @Test
    fun unsupportedCapabilitiesAreDroppedSilently() = runTest {
        val sink = RecordingSink()
        val runtime = RiveAvatarRuntime(sink).also { it.load(model()) }
        sink.writes.clear()

        runtime.setViseme(AvatarViseme.A, 1f)
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

    /**
     * A Rive mascot is not yet a first-class [AvatarFormat] - the import pipeline and the format
     * detector both switch exhaustively on that enum, and this spike does not go through either.
     * The runtime never reads the format, so the fixture borrows the non-humanoid one.
     */
    private fun model() = AvatarModel(
        id = "mascot",
        displayName = "Mascot",
        uri = "asset://mascot.riv",
        format = AvatarFormat.GLB,
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
