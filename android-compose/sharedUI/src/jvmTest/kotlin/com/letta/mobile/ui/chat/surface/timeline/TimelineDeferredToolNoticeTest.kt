@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.chat.surface.timeline

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.letta.mobile.data.timeline.TIMELINE_EVENT_CONTENT_TYPE
import com.letta.mobile.data.timeline.TimelineBodyPointer
import com.letta.mobile.data.timeline.TimelineBodyReference
import com.letta.mobile.data.timeline.TimelineBoundedReader
import com.letta.mobile.data.timeline.TimelineBoundedStore
import com.letta.mobile.data.timeline.TimelineDurableCheckpoint
import com.letta.mobile.data.timeline.TimelineLedgerMetadata
import com.letta.mobile.data.timeline.TimelineMessageId
import com.letta.mobile.data.timeline.TimelineMetadataPage
import com.letta.mobile.data.timeline.TimelinePageKey
import com.letta.mobile.data.timeline.TimelineReadPosition
import com.letta.mobile.data.timeline.TimelineSemanticBodyWindow
import com.letta.mobile.data.timeline.TimelineSemanticField
import com.letta.mobile.data.timeline.TimelineSemanticWindowResult
import com.letta.mobile.data.timeline.TimelineStoreReader
import com.letta.mobile.data.timeline.TimelineStoreTransaction
import com.letta.mobile.data.timeline.TimelineToolIndexEntry
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * letta-mobile-032ia: a held-back tool call names the tool it belongs to, so the card never reads as
 * a nameless "message too large" for a row that plainly ran something.
 */
class TimelineDeferredToolNoticeTest {

    private fun text(value: String, type: String = "tool_call") =
        TimelineSemanticWindowResult.Text(value, null, 20, value.length, 1, type)

    private fun missing(type: String = "tool_call") =
        TimelineSemanticWindowResult.Deferred(TimelineSemanticWindowResult.Reason.MissingField, type)

    private fun ComposeUiTest.show(read: DeferredBodyRead) {
        setContent { MaterialTheme { TimelineDeferredBody("row", read) } }
    }

    @Test
    fun aHeldBackToolCallNamesItsTool() = runComposeUiTest {
        show { field, _ ->
            when (field) {
                TimelineSemanticField.toolName() -> text("Bash")
                TimelineSemanticField.ToolReturnByCallId -> text("y")
                else -> missing()
            }
        }
        onNodeWithText("Ran Bash · output too large to show inline").assertExists()
        onNodeWithText("View content").assertExists()
    }

    @Test
    fun aHeldBackMessageKeepsThePlainNotice() = runComposeUiTest {
        show { _, _ -> missing("assistant") }
        onNodeWithText("Message too large to show inline").assertExists()
    }

    /**
     * The name lookup reads the stored body on its own, before anything is asked for, so a body the
     * store can no longer resolve leaves it throwing. Here the reader is the production
     * [TimelineSemanticBodyWindow.read] over a store whose row moved to a later page revision after
     * the pointer was taken: the real stale-reference path, which raises IllegalStateException from
     * the reader rather than returning a value. The row must still draw, with its plain notice.
     */
    @Test
    fun aFailedToolNameLookupKeepsThePlainNotice() = runComposeUiTest {
        val body = """{"messageType":"$TOOL_CALL","toolCalls":[{"name":"Bash","arguments":"{}"}]}"""
        val stored = StaleChunkStore(body)
        show { field, offset ->
            TimelineSemanticBodyWindow.read(stored.reference, field, offset, readChunk = stored::chunk)
        }
        onNodeWithText("Message too large to show inline").assertExists()
    }

    @Test
    fun theNoticeIsComposedFromTheToolNameOnly() {
        assertEquals("Message too large to show inline", deferredNotice(null))
        assertEquals("Ran python3 · output too large to show inline", deferredNotice("python3"))
    }

    /**
     * The store behind a deferred body once the page it points at has moved on: its metadata page
     * carries a later revision, exactly what [TimelineBoundedReader.readChunk] refuses.
     */
    private class StaleChunkStore(body: String) : TimelineBoundedStore {
        private val scope = TimelineScope(backendId = "backend", conversationId = "conversation")
        private val key = TimelinePageKey(order = 1, identity = TimelineMessageId("row"))
        private val bytes = body.encodeToByteArray()

        val reference = TimelineBodyReference(
            scope = scope,
            key = key,
            pointer = TimelineBodyPointer(value = "row", encodedBytes = bytes.size.toLong()),
            contentType = TIMELINE_EVENT_CONTENT_TYPE,
            revision = STALE_BODY_REVISION,
        )

        private val reader = object : TimelineStoreReader {
            override suspend fun toolCall(callId: String): TimelineToolIndexEntry? = null

            override suspend fun unresolvedTools(afterCallId: String?, maxRows: Int) = emptyList<TimelineToolIndexEntry>()

            override suspend fun toolSweepGeneration(): Long = 0

            override suspend fun checkpoint() = TimelineDurableCheckpoint(EMPTY_REVISION, continuation = null, hasMore = false)

            override suspend fun metadata(position: TimelineReadPosition, maxRows: Int) = TimelineMetadataPage(
                rows = listOf(
                    TimelineLedgerMetadata(
                        key = key,
                        body = reference.pointer,
                        contentType = TIMELINE_EVENT_CONTENT_TYPE,
                        revision = reference.revision,
                    ),
                ),
                older = null,
                newer = null,
                // The page moved on since the row took its pointer: the chunk read must refuse it.
                revision = reference.revision + 1,
            )

            override suspend fun locate(identity: TimelineMessageId): TimelinePageKey? = null

            override suspend fun body(pointer: TimelineBodyPointer, offset: Long, maxBytes: Int): ByteArray =
                bytes.copyOfRange(offset.toInt(), minOf(bytes.size, offset.toInt() + maxBytes))

            override suspend fun evidence(key: String, maxBytes: Int): ByteArray? = null
        }

        /** The store as the pager sees it: one chunk read through the production bounded reader. */
        suspend fun chunk(offset: Long, maxBytes: Int) = TimelineBoundedReader(this).readChunk(reference, offset, maxBytes)

        override suspend fun <T> read(scope: TimelineScope, block: suspend TimelineStoreReader.() -> T): T = reader.block()

        override suspend fun <T> transaction(scope: TimelineScope, block: suspend TimelineStoreTransaction.() -> T): T =
            throw UnsupportedOperationException("A deferred body is only ever read")
    }

    private companion object {
        const val TOOL_CALL = "tool_call"
        const val STALE_BODY_REVISION = 7L
        const val EMPTY_REVISION = 0L
    }
}
