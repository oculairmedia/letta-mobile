package com.letta.mobile.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.jupiter.api.Tag

@Tag("unit")
class TopLevelDestinationTest {

    @Test
    fun `all 3 destinations have non-null icons`() {
        for (dest in TopLevelDestination.entries) {
            assertNotNull(dest.icon)
        }
    }

    @Test
    fun `all 3 destinations have non-empty labels`() {
        for (dest in TopLevelDestination.entries) {
            assertTrue(dest.label.isNotBlank())
        }
    }

    @Test
    fun `HOME route is HomeRoute`() {
        assertEquals(HomeRoute, TopLevelDestination.HOME.route)
    }

    @Test
    fun `CHAT route is ConversationsRoute`() {
        assertEquals(ConversationsRoute, TopLevelDestination.CHAT.route)
    }

    @Test
    fun `ADMIN route is AdminRoute`() {
        assertEquals(AdminRoute, TopLevelDestination.ADMIN.route)
    }

    @Test
    fun `all 3 destinations have unique labels`() {
        val labels = TopLevelDestination.entries.map { it.label }.toSet()
        assertEquals(3, labels.size)
    }
}
