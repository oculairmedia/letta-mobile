package com.letta.mobile.desktop

import com.letta.mobile.data.lens.LensDestination
import com.letta.mobile.data.lens.WorkPlayMode
import com.letta.mobile.desktop.chat.ConversationArchiveFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-ckmub.3.10: the desktop binds the shared agent panel; only routing is the desktop's own. */
class DesktopSidebarBindingTest {

    @Test
    fun theOpenDestinationBecomesTheSelectedSection() {
        assertEquals(LensDestination.Skills, page(DesktopDestination.Agents).selectedSection)
        assertEquals(LensDestination.Memory, page(DesktopDestination.Memory).selectedSection)
        assertNull(page(DesktopDestination.Settings).selectedSection)
        assertTrue(page(DesktopDestination.Settings).settingsSelected)
        assertTrue(page(DesktopDestination.Home).home)
        assertFalse(page(DesktopDestination.Memory).home)
    }

    /** letta-mobile-zxk2n.32: Skills lands on the destination that draws the shared Skills page. */
    @Test
    fun skillsOpensTheSkillsPageDestination() {
        WorkPlayMode.entries.forEach { mode ->
            assertEquals(DesktopDestination.Agents, lensNavTarget(mode, LensDestination.Skills).first)
        }
    }

    @Test
    fun aConversationIsOpenOnlyWhileTheConversationPageShows() {
        assertEquals("conv-1", desktopOpenConversation(DesktopDestination.Conversations, "conv-1"))
        assertNull(desktopOpenConversation(DesktopDestination.Memory, "conv-1"))
    }

    @Test
    fun theArchiveFilterRoundTrips() {
        ConversationArchiveFilter.entries.forEach { assertEquals(it, it.toShellArchiveFilter().toConversationArchiveFilter()) }
    }

    @Test
    fun chatOptionsFollowTheWindowsFontScaleAndOpenChat() {
        val options = desktopChatOptionsState(
            display = com.letta.mobile.data.chat.display.ChatDisplayOptions.Default,
            fontScale = 1f,
            autoPin = true,
            hasChat = false,
        )
        assertTrue(options.autoPinNewChats)
        assertFalse(options.canDownloadTranscript)
    }

    private fun page(destination: DesktopDestination) = desktopPanelPage(destination, WorkPlayMode.Work)
}
