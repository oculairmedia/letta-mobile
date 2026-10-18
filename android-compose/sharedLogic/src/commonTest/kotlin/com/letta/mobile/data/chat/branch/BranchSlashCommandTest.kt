package com.letta.mobile.data.chat.branch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** letta-mobile-7mys7: `/fork [name]` and `/btw <question>` parse as letta-code's terminal UI does. */
class BranchSlashCommandTest {
    @Test
    fun forkTakesAnOptionalName() {
        assertEquals(BranchSlashCommand.Fork(null), BranchSlashCommand.parse("/fork"))
        assertEquals(BranchSlashCommand.Fork(null), BranchSlashCommand.parse("  /fork  "))
        assertEquals(BranchSlashCommand.Fork("Plan B"), BranchSlashCommand.parse("/fork   Plan B "))
    }

    @Test
    fun btwNeedsAQuestion() {
        assertEquals(BranchSlashCommand.Aside("what is X?"), BranchSlashCommand.parse("/btw what is X?"))
        assertNull(BranchSlashCommand.parse("/btw"))
    }

    @Test
    fun commandsPreserveMultilineArguments() {
        assertEquals(BranchSlashCommand.Fork("Plan\nB"), BranchSlashCommand.parse("/fork Plan\nB"))
        assertEquals(BranchSlashCommand.Aside("why?\nExplain"), BranchSlashCommand.parse("/btw why?\nExplain"))
    }

    @Test
    fun otherTextIsAMessage() {
        assertNull(BranchSlashCommand.parse("/forklift now"))
        assertNull(BranchSlashCommand.parse("please /fork this"))
        assertNull(BranchSlashCommand.parse("/compact"))
    }
}
