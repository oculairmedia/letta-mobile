package com.letta.mobile.avatar.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MascotIdentityTest {
    @Test
    fun `round trips through the persisted form`() {
        val identity = MascotIdentity(MascotShape.CLOUD, MascotPalette.PINK)
        assertEquals("cloud:ffe0457b", identity.encode())
        assertEquals(identity, MascotIdentity.decode(identity.encode()))
    }

    @Test
    fun `a legacy avatar style index becomes a circle in that gradient's lead colour`() {
        assertEquals(MascotIdentity(MascotShape.CIRCLE, MascotPalette.BLUE), MascotIdentity.decode("2"))
        // Out-of-range indexes wrapped in the old code too.
        assertEquals(MascotIdentity(MascotShape.CIRCLE, MascotPalette.AMBER), MascotIdentity.decode("6"))
    }

    @Test
    fun `garbage reads as nothing rather than a default`() {
        assertNull(MascotIdentity.decode(null))
        assertNull(MascotIdentity.decode(""))
        assertNull(MascotIdentity.decode("octagon:ff000000"))
        assertNull(MascotIdentity.decode("circle:notahex"))
    }
}
