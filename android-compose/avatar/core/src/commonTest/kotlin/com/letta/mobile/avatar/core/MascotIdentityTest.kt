package com.letta.mobile.avatar.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MascotIdentityTest {
    @Test
    fun `round trips through the persisted form`() {
        val identity = MascotIdentity(MascotShape.CLOUD, MascotPalette.PINK)
        assertEquals("cloud:ffe0457b", identity.encode())
        assertEquals(identity, MascotIdentity.decode(identity.encode()))
    }

    @Test
    fun `a turned identity carries its rotation and an unturned one keeps the old form`() {
        val turned = MascotIdentity(MascotShape.TRIANGLE, MascotPalette.TEAL, rotationDegrees = 135)
        assertEquals("triangle:ff14a08a:135", turned.encode())
        assertEquals(turned, MascotIdentity.decode(turned.encode()))
        assertEquals("triangle:ff14a08a", turned.copy(rotationDegrees = 0).encode())
    }

    @Test
    fun `a stored rotation outside one turn folds back into it`() {
        assertEquals(270, MascotIdentity.decode("pill:ff1e7bf0:-90")?.rotationDegrees)
        assertEquals(90, MascotIdentity.decode("pill:ff1e7bf0:450")?.rotationDegrees)
        assertFailsWith<IllegalArgumentException> { MascotIdentity(MascotShape.PILL, MascotPalette.BLUE, 360) }
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
        assertNull(MascotIdentity.decode("circle:1"))
        assertNull(MascotIdentity.decode("circle:ff0000"))
        assertNull(MascotIdentity.decode("circle:ff000000:north"))
        assertNull(MascotIdentity.decode("circle:ff000000:90:extra"))
    }

    @Test
    fun `a seeded identity is the same every time for the same agent`() {
        val id = "agent-7a1c4c8e-0d52-4b8f-9d0e-5b7f1f1c2a33"
        assertEquals(MascotIdentity.seeded(id), MascotIdentity.seeded(id))
    }

    @Test
    fun `seeded identities are pinned so existing agents never change look`() {
        // Golden values: if these move, every agent without a chosen identity changes its mascot.
        assertEquals(-0x340d631b7bdddcdbL, SeededRandom.seedOf(""))
        assertEquals(MascotIdentity.seeded("agent-1"), MascotIdentity.decode(MascotIdentity.seeded("agent-1").encode()))
        val pinned = listOf("agent-1", "agent-2", "agent-00000000-0000-0000-0000-000000000000").map { MascotIdentity.seeded(it).encode() }
        assertEquals(GOLDEN_SEEDED, pinned)
    }

    @Test
    fun `the lists generated identities draw from are frozen`() {
        // Any change here re-rolls every agent without a chosen identity: see SEEDED_SHAPES.
        assertEquals(
            listOf("CIRCLE", "BLOB", "ROUNDED_SQUARE", "PILL", "TRIANGLE", "HEXAGON", "CLOUD", "DROP"),
            MascotIdentity.SEEDED_SHAPES.map { it.name },
        )
        assertEquals(
            listOf(MascotPalette.BROWN, MascotPalette.RED, MascotPalette.ORANGE, MascotPalette.AMBER, MascotPalette.GREEN, MascotPalette.TEAL, MascotPalette.BLUE, MascotPalette.PURPLE, MascotPalette.PINK),
            MascotPalette.SEEDED,
        )
    }

    @Test
    fun `seeded identities use saturated colours and 45 degree turns and spread over the options`() {
        val identities = (0 until 400).map { MascotIdentity.seeded("agent-$it") }
        assertTrue(identities.all { it.argb in MascotPalette.SEEDED })
        assertTrue(identities.all { it.rotationDegrees % MascotIdentity.SEEDED_ROTATION_STEP == 0 })
        assertEquals(MascotIdentity.SEEDED_SHAPES.toSet(), identities.map { it.shape }.toSet())
        assertEquals(MascotPalette.SEEDED.toSet(), identities.map { it.argb }.toSet())
        assertEquals(8, identities.map { it.rotationDegrees }.toSet().size)
        assertNotEquals(MascotIdentity.seeded("agent-1"), MascotIdentity.seeded("agent-2"))
    }

    private companion object {
        val GOLDEN_SEEDED = listOf("blob:ff1e7bf0:90", "cloud:ffe5484d", "hexagon:ff8b5a2b:225")
    }

    @Test
    fun `partway between two identities the shape is already the target and the colour is mixed`() {
        val from = MascotIdentity(MascotShape.CIRCLE, 0xFF000000.toInt())
        val to = MascotIdentity(MascotShape.TRIANGLE, 0xFFFFFFFF.toInt())
        assertEquals(MascotIdentity(MascotShape.TRIANGLE, 0xFF000000.toInt()), MascotIdentity.lerp(from, to, 0f))
        assertEquals(MascotIdentity(MascotShape.TRIANGLE, 0xFF808080.toInt()), MascotIdentity.lerp(from, to, 0.5f))
        assertEquals(to, MascotIdentity.lerp(from, to, 1f))
    }

    @Test
    fun `a morph turns the body the shorter way round`() {
        val from = MascotIdentity(MascotShape.PILL, MascotPalette.BLUE, rotationDegrees = 350)
        val to = MascotIdentity(MascotShape.PILL, MascotPalette.BLUE, rotationDegrees = 10)
        assertEquals(0, MascotIdentity.lerp(from, to, 0.5f).rotationDegrees)
        assertEquals(355, MascotIdentity.lerp(from, to, 0.25f).rotationDegrees)
        assertEquals(180, MascotIdentity.lerp(to.copy(rotationDegrees = 90), to.copy(rotationDegrees = 270), 0.5f).rotationDegrees)
    }
}
