@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.model.UiMessage
import com.letta.mobile.ui.chat.render.ChatUiState
import com.letta.mobile.ui.chat.render.ConversationState
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.ChatSurfacePresentation
import com.letta.mobile.ui.chat.surface.timeline.TimelineDays
import com.letta.mobile.ui.chat.surface.timeline.parseTimelineLocalDate
import com.letta.mobile.ui.markdown.LocalSettledMarkdownParses
import com.letta.mobile.ui.markdown.SettledMarkdownParses
import com.letta.mobile.ui.markdown.StreamingMarkdownDocument
import com.letta.mobile.ui.markdown.StreamingMarkdownDocumentState
import com.letta.mobile.ui.markdown.rememberMarkdownTable
import com.letta.mobile.ui.markdown.rememberStreamingMarkdownDocument
import kotlinx.collections.immutable.toPersistentList
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Rows the timeline has already drawn are not drawn from scratch again when nothing about them
 * changed: expanding the docked chat starts from the parses the page kept, and the rich renderer's
 * settled documents and tables are parsed once per page.
 */
class TimelineRemountTest {
    /**
     * The page keeps its settled parses with its scroll position, not per list: the full page's list
     * is torn down while the chat is docked, and expanding again draws its rows from the kept parse
     * on the first frame, at their final height (a list of its own started from the empty loading
     * box and grew every visible row in again).
     */
    @Test
    fun expandingFromDockedStartsFromThePagesKeptParses() = runComposeUiTest {
        var presentation by mutableStateOf(ChatSurfacePresentation.ChatFirst)
        val port = ChatSurfaceUiTest.TestPort(
            ChatUiState(
                conversationState = ConversationState.Ready("conv-remount"),
                isLoadingMessages = false,
                messages = listOf(
                    UiMessage("u1", "user", "Plan the week", "2026-11-17T09:00:00Z"),
                    UiMessage("a1", "assistant", REPLY, "2026-11-17T09:00:05Z"),
                ).toPersistentList(),
            ),
        )
        setContent {
            MaterialTheme {
                Box(Modifier.size(width = 900.dp, height = 1200.dp)) {
                    ChatSurface(port = port, presentation = presentation, onIntent = {}, host = ChatSurfaceHost())
                }
            }
        }
        waitUntil(timeoutMillis = PARSE_TIMEOUT_MILLIS) { replyShown() }
        waitForIdle()

        presentation = ChatSurfacePresentation.CanvasFirst
        waitForIdle()
        val docked = replyCopies()
        mainClock.autoAdvance = false
        presentation = ChatSurfacePresentation.ChatFirst
        mainClock.advanceTimeByFrame()
        // The docked panel's copy is still fading out; the full page's own copy is already its text
        // (a list with parses of its own drew the empty loading box here and grew the text in later).
        assertTrue(replyCopies() > docked, "the full page's reply is its text on the first frame after expanding")
    }

    /** A settled text's block document and its table parse once per page, whatever composes them again. */
    @Test
    fun theRichRenderersSettledDocumentAndTablesParseOncePerPage() = runComposeUiTest {
        val parses = SettledMarkdownParses()
        var shown by mutableStateOf(true)
        val documents = mutableListOf<StreamingMarkdownDocument>()
        setContent {
            CompositionLocalProvider(LocalSettledMarkdownParses provides parses) {
                if (shown) {
                    // A row composed afresh: its own (empty) document state, as after a scroll back.
                    val state = remember { StreamingMarkdownDocumentState() }
                    documents += rememberStreamingMarkdownDocument(TABLE_REPLY, state, isStreaming = false)
                    rememberMarkdownTable(TABLE, settled = true)
                }
            }
        }
        waitForIdle()
        shown = false
        waitForIdle()
        shown = true
        waitForIdle()
        assertTrue(documents.size >= 2, "the row composed twice")
        assertSame(documents.first(), documents.last(), "the row composed again started from the kept document")
        assertNotNull(parses.table(TABLE), "the settled table is kept by its source")
        assertSame(parses.document(TABLE_REPLY), documents.last())
    }

    /** The list's day of a timestamp is the parsed one, kept: the same answers, invalid stamps included. */
    @Test
    fun aListsDaysAreTheParsedDays() {
        val days = TimelineDays(TimeZone.UTC)
        listOf("2026-11-17T23:59:00Z", "2026-11-17T23:59:00Z", "2026-11-18T00:00:00+01:00", "", "not-a-date", "2026-11-18T07:00:00")
            .forEach { stamp -> assertEquals(parseTimelineLocalDate(stamp, TimeZone.UTC), days.of(stamp), stamp) }
    }

    private fun ComposeUiTest.replyShown(): Boolean =
        onAllNodes(hasText(REPLY_FIRST_LINE, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.replyCopies(): Int =
        onAllNodes(hasText(REPLY_FIRST_LINE, substring = true), useUnmergedTree = true).fetchSemanticsNodes().size

    private companion object {
        const val PARSE_TIMEOUT_MILLIS = 20_000L
        const val REPLY_FIRST_LINE = "Monday holds the review"
        const val REPLY = "$REPLY_FIRST_LINE, and\n\n- Tuesday the **launch**\n- Wednesday the retro\n\nThe rest stays open."
        const val TABLE = "| day | plan |\n| --- | --- |\n| Mon | review |\n| Tue | launch |"
        const val TABLE_REPLY = "The week:\n\n$TABLE\n\nThat's all."
    }
}
