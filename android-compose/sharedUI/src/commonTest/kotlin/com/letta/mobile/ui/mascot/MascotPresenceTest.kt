package com.letta.mobile.ui.mascot

import com.letta.mobile.avatar.core.AvatarDirector
import com.letta.mobile.avatar.core.AvatarState
import com.letta.mobile.avatar.core.GazePose
import com.letta.mobile.avatar.core.GazeTarget
import com.letta.mobile.avatar.core.GazeWorld
import com.letta.mobile.avatar.core.HeadlessAvatarRuntime
import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.avatar.core.MascotShape
import com.letta.mobile.data.presence.AgentActivityKind
import com.letta.mobile.data.presence.AgentPresence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MascotPresenceTest {
    private class Recorded(val director: AvatarDirector, val entered: MutableList<AvatarState>)

    private fun director(): Recorded {
        val entered = mutableListOf<AvatarState>()
        val director = AvatarDirector(HeadlessAvatarRuntime()).also { d -> d.addStateListener { _, enter -> entered += enter } }
        return Recorded(director, entered)
    }

    @Test
    fun runIsThinkingThenSpeakingThenSucceeds() {
        val (director, entered) = director().let { it.director to it.entered }
        val thinking = AgentPresence(activity = AgentActivityKind.THINKING)
        val speaking = AgentPresence(activity = AgentActivityKind.SPEAKING)
        director.applyPresence(AgentPresence.IDLE, thinking)
        director.applyPresence(thinking, speaking)
        director.applyPresence(speaking, AgentPresence.IDLE)
        assertEquals(listOf(AvatarState.THINKING, AvatarState.SPEAKING), entered.take(2))
        assertTrue(AvatarState.SUCCESS in entered, "a clean end is a completed task: $entered")
    }

    @Test
    fun errorRisingEdgeIsTheCueAndNoSuccessFollows() {
        val (director, entered) = director().let { it.director to it.entered }
        val thinking = AgentPresence(activity = AgentActivityKind.THINKING)
        val failed = AgentPresence(error = true)
        director.applyPresence(AgentPresence.IDLE, thinking)
        director.applyPresence(thinking, failed)
        director.applyPresence(failed, failed.copy(userTyping = true))
        assertEquals(AvatarState.THINKING, entered.first())
        assertEquals(1, entered.count { it == AvatarState.ERROR })
        assertEquals(0, entered.count { it == AvatarState.SUCCESS })
    }

    @Test
    fun entryFeedsPresenceDeltasAndTicksOncePerFrame() {
        var loads = 0
        var ticks = 0
        val entry = object : MascotEntry(HeadlessAvatarRuntime(), MascotIdentity(MascotShape.entries.first(), 0xFF00AA88.toInt()), applyState = {}) {
            override suspend fun load() { loads++ }
            override fun dispose() = Unit
        }
        entry.director.addStateListener { _, _ -> ticks++ }
        entry.apply(AgentPresence(userTyping = true))
        entry.apply(AgentPresence(userTyping = true)) // no change, no second transition
        assertEquals(1, ticks)
        entry.tickTo(1_000_000_000L)
        entry.tickTo(1_000_000_000L) // same frame: idempotent
        entry.tickTo(1_016_000_000L)
        assertEquals(0, loads)
    }

    @Test
    fun entryTicksGazeDirectorWhenThePointerIsAbsent() {
        val entry = object : MascotEntry(HeadlessAvatarRuntime(), MascotIdentity(MascotShape.entries.first(), 0xFF00AA88.toInt()), applyState = {}) {
            override suspend fun load() = Unit
            override fun dispose() = Unit
        }
        entry.setGazeWorld(GazeWorld())
        entry.tickTo(1_000_000_000L)
        assertEquals(GazePose.CENTER, entry.lastGaze) // first frame is dt = 0
        entry.tickTo(1_016_000_000L)
        val pose = entry.lastGaze
        assertTrue(pose.lookTarget.x in 0f..1f && pose.lookTarget.y in 0f..1f)
        assertTrue(
            pose.target == GazeTarget.OWN || pose.target == GazeTarget.USER,
            "without a pointer the plan still runs: ${pose.target}",
        )
    }
}
