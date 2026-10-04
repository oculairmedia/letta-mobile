package com.letta.mobile.ui.haptics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HapticPolicyTest {
    private var now = 0L
    private var enabled = true
    private var foreground = true
    private var reducedMotion = false
    private val fake = FakeHaptics { now }
    private val policy = HapticPolicy(
        backend = fake,
        clock = { now },
        gates = HapticGates({ enabled }, { foreground }, { reducedMotion }),
    )

    private fun playAt(millis: Long, cue: LettaHapticCue) {
        now = millis
        policy.play(cue)
    }

    private fun playEveryEventSpacedApart() {
        LettaHapticCue.entries.forEachIndexed { i, e -> playAt(i * 1000L, e) }
    }

    @Test
    fun disabledSettingDropsEverything() {
        enabled = false
        playEveryEventSpacedApart()
        assertTrue(fake.played.isEmpty())
    }

    @Test
    fun backgroundDropsEverything() {
        foreground = false
        playEveryEventSpacedApart()
        assertTrue(fake.played.isEmpty())
    }

    @Test
    fun reducedMotionDropsOnlyMotionCoupledEvents() {
        reducedMotion = true
        playEveryEventSpacedApart()
        assertEquals(LettaHapticCue.entries.filterNot { it.motionCoupled }, fake.events)
        assertEquals(
            setOf(LettaHapticCue.SendLaunch, LettaHapticCue.SendLand, LettaHapticCue.OverscrollBounce),
            LettaHapticCue.entries.filter { it.motionCoupled }.toSet(),
        )
    }

    @Test
    fun globalFloorCoalescesEventsWithinFiftyMillis() {
        playAt(1000, LettaHapticCue.Create)
        playAt(1049, LettaHapticCue.Navigate)
        playAt(1050, LettaHapticCue.MicStop)
        assertEquals(
            listOf(FakeHaptics.Played(LettaHapticCue.Create, 1000), FakeHaptics.Played(LettaHapticCue.MicStop, 1050)),
            fake.played,
        )
    }

    @Test
    fun snapIsLimitedToOnePerEightyMillis() {
        playAt(1000, LettaHapticCue.Snap)
        playAt(1060, LettaHapticCue.Snap)
        playAt(1079, LettaHapticCue.Snap)
        playAt(1080, LettaHapticCue.Snap)
        assertEquals(listOf(1000L, 1080L), fake.played.map { it.atMillis })
    }

    @Test
    fun droppedEventsDoNotAdvanceTheFloors() {
        enabled = false
        playAt(1000, LettaHapticCue.Snap)
        enabled = true
        playAt(1001, LettaHapticCue.Snap)
        assertEquals(listOf(1001L), fake.played.map { it.atMillis })
    }

    @Test
    fun replyArrivedFiresOncePerTurnAcrossFiftyTokens() {
        repeat(50) { token ->
            now = token * 100L
            policy.playOncePer(HapticTurnKey("turn-1"), LettaHapticCue.ReplyArrived)
        }
        assertEquals(listOf(LettaHapticCue.ReplyArrived), fake.events)
    }

    @Test
    fun aNewTurnResetsTheLatch() {
        now = 1000
        policy.playOncePer(HapticTurnKey("turn-1"), LettaHapticCue.ReplyArrived)
        now = 2000
        policy.playOncePer(HapticTurnKey("turn-1"), LettaHapticCue.ReplyArrived)
        now = 3000
        policy.playOncePer(HapticTurnKey("turn-2"), LettaHapticCue.ReplyArrived)
        assertEquals(listOf(1000L, 3000L), fake.played.map { it.atMillis })
    }

    @Test
    fun aCoalescedLatchedEventStillFiresOnItsNextTry() {
        playAt(1000, LettaHapticCue.Create)
        now = 1010
        policy.playOncePer(HapticTurnKey("t"), LettaHapticCue.ReplyArrived)
        now = 1060
        policy.playOncePer(HapticTurnKey("t"), LettaHapticCue.ReplyArrived)
        assertEquals(listOf(LettaHapticCue.Create, LettaHapticCue.ReplyArrived), fake.events)
    }
}
