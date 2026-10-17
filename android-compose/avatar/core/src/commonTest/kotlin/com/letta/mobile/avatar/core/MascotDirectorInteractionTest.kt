package com.letta.mobile.avatar.core

import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-te3n1.4: what the host's gestures do in the director - the reaction hook the seat's
 * body squashes on, and dizziness that does not repeat inside its cooldown.
 */
class MascotDirectorInteractionTest {
    private suspend fun director(): MascotDirector {
        val runtime = HeadlessAvatarRuntime().also { it.load(AvatarModel(id = "m", displayName = "M", uri = "res://raw/mascot.riv")) }
        return MascotDirector(runtime, Random(7), avatarConfig = AvatarDirector.Config(blinkMinInterval = 1_000f, blinkMaxInterval = 1_000f))
    }

    private fun MascotDirector.run(seconds: Float) {
        var left = seconds
        while (left > 0f) {
            tick(minOf(STEP, left))
            left -= STEP
        }
    }

    @Test
    fun theHookHearsExactlyWhatFiresAfterCooldowns() = runTest {
        val director = director()
        val heard = mutableListOf<Pair<AvatarReaction, Float>>()
        director.reactionListener = MascotReactionListener { reaction, arg -> heard += reaction to arg.value }
        director.onEvent(MascotEvent.Poke)
        director.onEvent(MascotEvent.Poke) // inside the poke cooldown: not fired, not heard
        director.run(0.5f)
        director.onEvent(MascotEvent.DoubleTap(trick = 3))
        director.onEvent(MascotEvent.DragStarted)
        director.onEvent(MascotEvent.DragEnded(impact = 0.4f))
        assertEquals(
            listOf(AvatarReaction.POKE to 0f, AvatarReaction.TRICK to 3f, AvatarReaction.LAND to 0.4f),
            heard,
        )
    }

    @Test
    fun dizzinessCoolsDownForTenSeconds() = runTest {
        val director = director()
        var dizzy = 0
        director.reactionListener = MascotReactionListener { reaction, _ -> if (reaction == AvatarReaction.DIZZY) dizzy++ }
        fun pokeFive() = repeat(5) {
            director.onEvent(MascotEvent.Poke)
            director.run(0.2f)
        }
        pokeFive()
        assertEquals(1, dizzy)
        pokeFive()
        assertEquals(1, dizzy, "still dizzy: no second spin inside the cooldown")
        director.run(10f)
        pokeFive()
        assertEquals(2, dizzy)
    }

    private companion object {
        const val STEP = 0.016f
    }
}
