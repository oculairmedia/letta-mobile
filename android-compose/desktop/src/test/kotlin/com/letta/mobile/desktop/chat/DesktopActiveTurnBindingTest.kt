package com.letta.mobile.desktop.chat

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DesktopActiveTurnBindingTest {
    @Test
    fun ownerCannotBeReboundToAnotherAgent() = runTest {
        val binding = DesktopActiveTurnBinding(backgroundScope) {}
        binding.owner("conversation", "agent-a")
        assertFailsWith<IllegalArgumentException> { binding.owner("conversation", "agent-b") }
        binding.close()
    }
}
