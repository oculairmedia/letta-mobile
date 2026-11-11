package com.letta.mobile.wrapper.notebook

import com.github.ajalt.clikt.core.UsageError
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class NotebookHostDurabilityTest {
    @Test
    fun unsupportedHostIsAUsageErrorAtBothCommandEdges() {
        val failure = assertThrows(UsageError::class.java) { requireNotebookHostDurability(supported = false) }
        assertEquals(
            "Notebook sharing needs a host that can confirm directory durability; not available on a Windows host",
            failure.message,
        )
    }

    @Test
    fun supportedHostCanStartNotebookSharing() {
        requireNotebookHostDurability(supported = true)
    }
}
