package com.letta.mobile.data.health

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ServerHealthStateTest {

    @Test
    fun testServerHealthStateValues() {
        val states = ServerHealthState.values()
        assertEquals(4, states.size)
        assertEquals(ServerHealthState.UNKNOWN, states[0])
        assertEquals(ServerHealthState.PROBING, states[1])
        assertEquals(ServerHealthState.ONLINE, states[2])
        assertEquals(ServerHealthState.OFFLINE, states[3])
    }

    @Test
    fun testValueOf() {
        assertEquals(ServerHealthState.UNKNOWN, ServerHealthState.valueOf("UNKNOWN"))
        assertEquals(ServerHealthState.PROBING, ServerHealthState.valueOf("PROBING"))
        assertEquals(ServerHealthState.ONLINE, ServerHealthState.valueOf("ONLINE"))
        assertEquals(ServerHealthState.OFFLINE, ServerHealthState.valueOf("OFFLINE"))
    }

    @Test
    fun testValueOf_invalid() {
        assertFailsWith<IllegalArgumentException> {
            ServerHealthState.valueOf("INVALID")
        }
    }
}
