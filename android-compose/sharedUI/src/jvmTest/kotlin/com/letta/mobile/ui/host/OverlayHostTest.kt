@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.host

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.runtime.ConversationDeleteBehavior
import com.letta.mobile.ui.shell.UndoDeleteOverlay
import com.letta.mobile.ui.shell.undoDeleteMessage
import com.letta.mobile.ui.theme.LocalReducedMotion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** letta-mobile-ckmub.3.2 / ckmub.3.15 (S13, P10): the one overlay queue. */
class OverlayHostTest {
    private var now = 0L
    private val host = OverlayHost(clock = { now })

    @Test
    fun oneSnackbarShowsAndTheRestWaitInOrder() {
        host.show(Overlay.Snackbar("first"))
        host.show(Overlay.Snackbar("second"))
        host.show(Overlay.Snackbar("third"))
        assertEquals("first", host.snackbar?.overlay?.message)
        assertEquals(listOf("second", "third"), host.waiting.map { it.overlay.message })
        host.snackbar?.let { host.dismiss(it.id) }
        assertEquals("second", host.snackbar?.overlay?.message)
    }

    @Test
    fun theHighestPriorityWaitingSnackbarShowsNext() {
        host.show(Overlay.Snackbar("showing"))
        host.show(Overlay.Snackbar("low", presentation = OverlayPresentation(OverlayPriority.Low)))
        host.show(Overlay.Snackbar("normal"))
        host.show(Overlay.Snackbar("critical", presentation = OverlayPresentation(OverlayPriority.Critical)))
        // Critical outranks the one on screen, which waits to show again.
        assertEquals("critical", host.snackbar?.overlay?.message)
        assertEquals(listOf("showing", "low", "normal"), host.waiting.map { it.overlay.message })
        host.snackbar?.let { host.dismiss(it.id) }
        assertEquals("showing", host.snackbar?.overlay?.message)
        host.snackbar?.let { host.dismiss(it.id) }
        assertEquals("normal", host.snackbar?.overlay?.message)
        host.snackbar?.let { host.dismiss(it.id) }
        assertEquals("low", host.snackbar?.overlay?.message)
    }

    @Test
    fun theSameMessageTwiceShowsOnceAndAKeyReplacesInPlace() {
        val first = host.show(Overlay.Snackbar("Network error"))
        val again = host.show(Overlay.Snackbar("Network error"))
        assertEquals(first.id, again.id)
        assertTrue(host.waiting.isEmpty())

        host.show(Overlay.Snackbar("Recompiling…", key = "recompile", presentation = OverlayPresentation.Sticky))
        host.show(Overlay.Snackbar("Recompiled", key = "recompile"))
        assertEquals(listOf("Recompiled"), host.waiting.map { it.overlay.message })

        host.show(Overlay.Banner("Runtime restarting", key = "runtime"))
        host.show(Overlay.Banner("Runtime stopped", key = "runtime"))
        assertEquals(listOf("Runtime stopped"), host.banners.map { it.overlay.message })
    }

    @Test
    fun anUndoCommitsWhenItsTimeIsUpAndNotBefore() {
        var undone = 0
        var committed = 0
        host.show(Overlay.undo("Moved to Archived", key = "undo", onUndo = { undone++ }, onCommit = { committed++ }))
        assertEquals(OverlayDurations.LONG, host.nextDeadlineIn())
        now = OverlayDurations.LONG - 1
        host.tick()
        assertEquals(0, committed)
        assertEquals(1L, host.nextDeadlineIn())
        now = OverlayDurations.LONG
        host.tick()
        assertEquals(1, committed)
        assertEquals(0, undone)
        assertNull(host.snackbar)
        assertNull(host.nextDeadlineIn())
    }

    @Test
    fun takingTheUndoRunsItAndNeverCommits() {
        var undone = 0
        var committed = 0
        val handle = host.show(Overlay.undo("Chat deleted", key = "undo", onUndo = { undone++ }, onCommit = { committed++ }))
        host.performAction(handle.id)
        now = OverlayDurations.LONG * 2
        host.tick()
        assertEquals(1, undone)
        assertEquals(0, committed)
        assertFalse(handle.isActive)
    }

    @Test
    fun aWaitingSnackbarsTimeStartsWhenItReachesTheScreen() {
        host.show(Overlay.Snackbar("first", presentation = OverlayPresentation(durationMillis = 1_000)))
        host.show(Overlay.Snackbar("second", presentation = OverlayPresentation(durationMillis = 1_000)))
        now = 1_000
        host.tick()
        assertEquals("second", host.snackbar?.overlay?.message)
        now = 1_999
        host.tick()
        assertEquals("second", host.snackbar?.overlay?.message)
        now = 2_000
        host.tick()
        assertNull(host.snackbar)
    }

    @Test
    fun withdrawingIsSilentAndDismissingIsNot() {
        var dismissed = 0
        val banner = host.show(Overlay.Banner("Offline", response = OverlayResponse(onDismissed = { dismissed++ })))
        banner.withdraw()
        assertEquals(0, dismissed)
        val again = host.show(Overlay.Banner("Offline", response = OverlayResponse(onDismissed = { dismissed++ })))
        again.dismiss()
        assertEquals(1, dismissed)
        assertTrue(host.banners.isEmpty())
    }

    @Test
    fun bannersShowTogetherHighestPriorityFirst() {
        host.show(Overlay.Banner("info"))
        host.show(Overlay.Banner("runtime down", presentation = OverlayPresentation(OverlayPriority.Critical, null, tone = OverlayTone.Error)))
        host.show(Overlay.Snackbar("saved"))
        assertEquals(listOf("runtime down", "info"), host.banners.map { it.overlay.message })
        assertEquals("saved", host.snackbar?.overlay?.message)
    }

    @Test
    fun theLayerDrawsTheSnackbarUntilASlotClaimsIt() = runComposeUiTest {
        var slot by mutableStateOf(false)
        setContent {
            MaterialTheme {
                OverlayHostRoot(host) {
                    Column { if (slot) Box(Modifier.testTag(SLOT)) { OverlaySnackbarSlot() } }
                }
            }
        }
        runOnIdle { host.show(Overlay.Snackbar("Saved", presentation = OverlayPresentation.Sticky)) }
        onNodeWithTag(OverlayTags.SNACKBAR).assertExists()
        slot = true
        waitForIdle()
        onAllNodes(hasTestTag(OverlayTags.SNACKBAR)).fetchSemanticsNodes().let { assertEquals(1, it.size) }
        onNode(hasTestTag(OverlayTags.SNACKBAR) and hasAncestorTag(SLOT)).assertExists()
    }

    @Test
    fun theUndoBarUndoesOrCommitsTheDelete() = runComposeUiTest {
        val outcomes = mutableListOf<String>()
        var offer by mutableStateOf<String?>("conv-1")
        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalReducedMotion provides true) {
                    OverlayHostRoot(host, Modifier.size(400.dp)) {
                        UndoDeleteOverlay(
                            offerKey = offer,
                            behavior = ConversationDeleteBehavior.MovesToArchived,
                            onUndo = { outcomes += "undo:$offer"; offer = null },
                            onCommit = { outcomes += "commit:$offer"; offer = null },
                        )
                    }
                }
            }
        }
        onNodeWithText(undoDeleteMessage(ConversationDeleteBehavior.MovesToArchived)).assertExists()
        onNodeWithText(Overlay.UNDO_LABEL).performClick()
        waitForIdle()
        assertEquals(listOf("undo:conv-1"), outcomes)
        onNodeWithTag(OverlayTags.SNACKBAR).assertDoesNotExist()

        offer = "conv-2"
        waitForIdle()
        onNodeWithTag(OverlayTags.DISMISS).performClick()
        waitForIdle()
        assertEquals(listOf("undo:conv-1", "commit:conv-2"), outcomes)
    }

    @Test
    fun nothingPendingShowsNothing() = runComposeUiTest {
        setContent {
            MaterialTheme {
                OverlayHostRoot(host) { UndoDeleteOverlay(offerKey = null, behavior = null, onUndo = {}, onCommit = {}) }
            }
        }
        waitForIdle()
        onNodeWithTag(OverlayTags.SNACKBAR).assertDoesNotExist()
        assertNull(host.snackbar)
    }

    @Test
    fun theDeleteBarSaysWhereTheChatWent() {
        assertEquals("Moved to Archived", undoDeleteMessage(ConversationDeleteBehavior.MovesToArchived))
        assertEquals("Removed from your chats", undoDeleteMessage(ConversationDeleteBehavior.RemovesFromLists))
        assertEquals("Chat deleted", undoDeleteMessage(ConversationDeleteBehavior.Permanent))
    }

    @Test
    fun aBannerMirrorsItsOwnersState() = runComposeUiTest {
        var down by mutableStateOf(true)
        setContent {
            MaterialTheme {
                OverlayHostRoot(host) {
                    OverlayWhile(if (down) Overlay.Banner("Local runtime: Restarting…", presentation = OverlayPresentation(tone = OverlayTone.Error, durationMillis = null)) else null)
                }
            }
        }
        onNode(hasTestTag(OverlayTags.BANNER)).assertExists()
        onNodeWithText("Local runtime: Restarting…").assertExists()
        down = false
        waitForIdle()
        onNodeWithTag(OverlayTags.BANNER).assertDoesNotExist()
    }

    private companion object {
        const val SLOT = "slot"
    }
}

private fun hasAncestorTag(tag: String) = androidx.compose.ui.test.hasAnyAncestor(hasTestTag(tag))
