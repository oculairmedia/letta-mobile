package com.letta.mobile.data.shell

import com.letta.mobile.data.model.AgentId
import com.letta.mobile.data.model.ConversationId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A destination opened from outside (a link, a notification tap) becomes one validated startup entry. */
class ShellEntrySnapshotTest {
    @Test
    fun aChatWithAConversationIsAnExactEntryThatWinsStartupOverSavedFocus() {
        val entry = ShellDestination.Chat("agent", "conversation").entrySnapshot("source")
        val expected = CurrentFocus("chat", AgentId("agent"), ConversationId("conversation"), CurrentFocusChatMode.Exact)
        assertEquals(CurrentFocusSnapshot("source", expected), entry)
        val savedFocus = CurrentFocus("chat", AgentId("saved"), ConversationId("saved-c"), CurrentFocusChatMode.Exact)
        val saved = CurrentFocusSnapshot("source", savedFocus)
        val owner = ShellWorkspaceState()
        assertTrue(owner.initialize(CurrentFocusInputs("source", deepLink = entry, restored = saved)))
        assertEquals(expected, owner.currentFocus)
    }

    @Test
    fun anEditorEntryKeepsItsChatUnderneathAndAnInvalidTargetIsNoEntry() {
        val editor = ShellDestination.AgentEditor("agent").entrySnapshot("source")
        assertEquals(CurrentFocus("chat"), editor?.focus)
        assertEquals("agent-editor?agent=agent", editor?.editorRoute)
        assertNull(ShellDestination.Chat("agent", " ").entrySnapshot("source"))
        assertNull(ShellDestination.Chat("agent", "conversation").entrySnapshot(""))
    }
}
