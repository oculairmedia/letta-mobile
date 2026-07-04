package com.letta.mobile.avatar.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class AvatarDirectorTest {
    private val model = AvatarModel(
        id = "avatar-1",
        displayName = "Buddy",
        uri = "file:///buddy.vrm",
        format = AvatarFormat.VRM_1,
    )

    private suspend fun readyRuntime(): HeadlessAvatarRuntime =
        HeadlessAvatarRuntime().also { it.load(model) }

    /** min == max makes the blink schedule fully deterministic. */
    private fun fixedBlinkConfig() = AvatarDirector.Config(
        blinkMinInterval = 1f,
        blinkMaxInterval = 1f,
        blinkCloseSeconds = 0.1f,
    )

    @Test
    fun blinksOnScheduleAndReleases() = runTest {
        val runtime = readyRuntime()
        val director = AvatarDirector(runtime, Random(1), fixedBlinkConfig())

        director.tick(0.5f)
        assertTrue(runtime.expressionWeights["blink"] == null || runtime.expressionWeights["blink"] == 0f)

        director.tick(0.6f) // crosses the 1s schedule
        assertEquals(1f, runtime.expressionWeights["blink"])

        director.tick(0.11f) // past blinkCloseSeconds
        assertEquals(0f, runtime.expressionWeights["blink"])
    }

    @Test
    fun speechLevelDrivesTheMouthWhileSpeaking() = runTest {
        val runtime = readyRuntime()
        val director = AvatarDirector(runtime, Random(1), fixedBlinkConfig())

        director.setActivity(AvatarActivity.SPEAKING)
        director.setSpeechLevel(1f)
        director.tick(0.1f)

        assertEquals(1f, runtime.mouthOpen)
    }

    @Test
    fun speakingWithoutAudioLevelsChattersProcedurally() = runTest {
        val runtime = readyRuntime()
        val director = AvatarDirector(runtime, Random(7), fixedBlinkConfig())

        director.setActivity(AvatarActivity.SPEAKING)
        var sawOpenMouth = false
        repeat(40) {
            director.tick(0.05f) // 2 simulated seconds, no setSpeechLevel
            if (runtime.mouthOpen > 0.2f) sawOpenMouth = true
        }
        assertTrue(sawOpenMouth, "procedural chatter should open the mouth")
    }

    @Test
    fun mouthClosesWhenSpeakingStops() = runTest {
        val runtime = readyRuntime()
        val director = AvatarDirector(runtime, Random(1), fixedBlinkConfig())
        director.setActivity(AvatarActivity.SPEAKING)
        director.setSpeechLevel(1f)
        director.tick(0.1f)
        assertEquals(1f, runtime.mouthOpen)

        director.setActivity(AvatarActivity.IDLE)
        repeat(10) { director.tick(0.1f) }

        assertTrue(runtime.mouthOpen < 0.05f, "mouth should release, was ${runtime.mouthOpen}")
    }

    @Test
    fun emotionFlashHoldsThenDecaysToZero() = runTest {
        val runtime = readyRuntime()
        val config = fixedBlinkConfig().copy(emotionHoldSeconds = 0.5f, emotionDecaySeconds = 0.5f)
        val director = AvatarDirector(runtime, Random(1), config)

        director.flashEmotion(AvatarExpression.Surprised)
        assertEquals(1f, runtime.expressionWeights["surprised"])

        director.tick(0.4f) // still holding
        assertEquals(1f, runtime.expressionWeights["surprised"])

        repeat(12) { director.tick(0.1f) } // through hold remainder + decay
        assertEquals(0f, runtime.expressionWeights["surprised"])
    }

    @Test
    fun activityTransitionsSwapBaseExpressions() = runTest {
        val runtime = readyRuntime()
        val director = AvatarDirector(runtime, Random(1), fixedBlinkConfig())

        director.setActivity(AvatarActivity.THINKING)
        // THINKING relaxed 0.6wt attacks over 0.4s (§6): ramps from 0, so tick
        // to let the base expression rise before asserting.
        director.tick(0.4f)
        val relaxed = runtime.expressionWeights["relaxed"]
        assertNotNull(relaxed)
        assertTrue(relaxed > 0f)

        director.setActivity(AvatarActivity.SPEAKING)
        assertEquals(0f, runtime.expressionWeights["relaxed"])
        assertTrue((runtime.expressionWeights["happy"] ?: 0f) > 0f)

        director.setActivity(AvatarActivity.IDLE)
        assertEquals(0f, runtime.expressionWeights["happy"])
    }

    @Test
    fun errorActivityFlashesSad() = runTest {
        val runtime = readyRuntime()
        val director = AvatarDirector(runtime, Random(1), fixedBlinkConfig())

        director.setActivity(AvatarActivity.ERROR)

        // ERROR sad 0.8wt (§6 state matrix).
        assertEquals(0.8f, runtime.expressionWeights["sad"])
    }

    @Test
    fun lookTargetPassesThrough() = runTest {
        val runtime = readyRuntime()
        val director = AvatarDirector(runtime, Random(1), fixedBlinkConfig())

        director.setLookTarget(AvatarLookTarget.Screen(0.25f, 0.75f))
        assertEquals(AvatarLookTarget.Screen(0.25f, 0.75f), runtime.lookTarget)

        director.setLookTarget(null)
        assertEquals(null, runtime.lookTarget)
    }

    @Test
    fun directorIsSafeAgainstAnUnloadedRuntime() {
        val runtime = HeadlessAvatarRuntime() // never loaded — commands no-op
        val director = AvatarDirector(runtime, Random(1), fixedBlinkConfig())

        director.setActivity(AvatarActivity.SPEAKING)
        director.setSpeechLevel(1f)
        director.flashEmotion(AvatarExpression.Happy)
        repeat(30) { director.tick(0.1f) }

        assertTrue(runtime.expressionWeights.isEmpty())
        assertEquals(0f, runtime.mouthOpen)
    }
}
