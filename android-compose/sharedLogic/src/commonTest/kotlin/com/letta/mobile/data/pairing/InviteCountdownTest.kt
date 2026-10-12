package com.letta.mobile.data.pairing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InviteCountdownTest {
    @Test
    fun formatsMinutesAndSecondsRoundingUp() {
        assertEquals("5:00", InviteCountdown.format(300_000))
        assertEquals("4:32", InviteCountdown.format(271_001))
        assertEquals("0:01", InviteCountdown.format(1))
        assertEquals("0:00", InviteCountdown.format(0))
        assertEquals("0:00", InviteCountdown.format(-5_000))
    }

    @Test
    fun announcesOnlyAtSixtyAndTenSeconds() {
        val announced = (300 downTo 0).zipWithNext().mapNotNull { (before, after) ->
            InviteCountdown.crossedThreshold(before * 1_000L, after * 1_000L)
        }
        assertEquals(listOf(60L, 10L), announced)
    }

    @Test
    fun aTickThatSkipsBothThresholdsAnnouncesOnlyTheNearerOne() {
        assertEquals(10L, InviteCountdown.crossedThreshold(65_000, 9_000))
        assertNull(InviteCountdown.crossedThreshold(65_000, 61_000))
        assertNull(InviteCountdown.crossedThreshold(5_000, 0), "expiry is a status change, not a countdown announcement")
    }

    @Test
    fun deviceAccessReflectsTheHostsCapabilities() {
        assertEquals(DeviceAccess.CHAT_ONLY, DeviceAccess.of(setOf("chat.read", "chat.send", "conversation.manage")))
        assertEquals(DeviceAccess.CHAT_AND_SETTINGS, DeviceAccess.of(setOf("chat.read", "memory.write")))
        assertEquals(DeviceAccess.FULL_ADMIN, DeviceAccess.of(setOf("admin.full")))
    }

    @Test
    fun groupsPutThisDeviceFirstAndOthersByRecency() {
        fun device(id: String, seen: Long?, agents: Set<String>? = null) =
            PairedDevice(id, id, pairedAtMs = 1, lastSeenAtMs = seen, capabilities = emptySet(), agentIds = agents)
        val groups = PairedDeviceGroups.of(listOf(device("a", 5), device("me", 1), device("b", 9, setOf("x"))), selfNodeId = "ME")
        assertEquals("me", groups.thisDevice?.nodeId)
        assertEquals(listOf("b", "a"), groups.others.map { it.nodeId })
        assertTrue(groups.canRevokeAllOthers)
        assertTrue(groups.anyAgentScoped)
        assertFalse(PairedDeviceGroups.of(listOf(device("a", 1), device("b", 1)), selfNodeId = null).canRevokeAllOthers)
    }
}
