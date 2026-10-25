package com.letta.mobile.ui.canvas.references

import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.canvas.NotebookDocument
import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.NotebookItem
import com.letta.mobile.data.canvas.references.NotebookReferenceAccess
import com.letta.mobile.data.canvas.references.NotebookReferenceLimits
import com.letta.mobile.data.canvas.references.NotebookReferenceLoad
import com.letta.mobile.data.canvas.references.NotebookReferencePhase
import com.letta.mobile.data.canvas.references.NotebookReferenceResolver
import com.letta.mobile.data.canvas.references.NotebookReferenceSession
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

@OptIn(ExperimentalTestApi::class)
class NotebookReferencePreviewTest {
    @Test
    fun compactReadOnlyMarkdownPreviewOpensTheExactTargetIdentityWithoutMountingEditor() = runComposeUiTest {
        val root = NotebookDocumentId("root")
        val target = NotebookDocumentId("00112233445566778899aabbccddeeff")
        val documents = mapOf(
            root to NotebookDocument(root, "Root", items = listOf(NotebookItem("embed", "reference", targetDocumentId = target))),
            target to NotebookDocument(target, "Referenced note", "Shared **Markdown** preview"),
        )
        val reads = mutableListOf<NotebookDocumentId>()
        val resolver = NotebookReferenceResolver(
            authorize = { NotebookReferenceAccess.ALLOWED },
            readAuthorized = { id -> reads.add(id); NotebookReferenceLoad.Found(documents.getValue(id)) },
        )
        val session = NotebookReferenceSession(root, resolver)
        var opened: NotebookDocumentId? = null
        val width = mutableStateOf(360.dp)
        setContent {
            MaterialTheme { NotebookReferencePreview(session, { opened = it }, Modifier.size(width.value, 640.dp)) }
        }
        onNodeWithText("Referenced note").assertIsDisplayed()
        onAllNodes(hasSetTextAction()).assertCountEquals(0)
        onNodeWithTag("notebook-note-editor").assertDoesNotExist()
        onAllNodesWithText("Open document")[1].performClick()
        runOnIdle {
            assertSame(target, opened)
            assertEquals(listOf(root, target), reads)
            width.value = 900.dp
        }
        onNodeWithText("Referenced note").assertIsDisplayed()
        runOnIdle { assertEquals(listOf(root, target), reads, "recomposition must not reload nodes") }
    }

    @Test
    fun deniedAndUnavailableTargetsShowClearPlaceholdersWithoutOpenActions() = runComposeUiTest {
        val root = NotebookDocumentId("root")
        val denied = NotebookDocumentId("denied")
        val unavailable = NotebookDocumentId("unavailable")
        val reads = mutableListOf<NotebookDocumentId>()
        val resolver = NotebookReferenceResolver(
            authorize = { if (it == denied) NotebookReferenceAccess.DENIED else NotebookReferenceAccess.ALLOWED },
            readAuthorized = {
                reads.add(it)
                if (it == root) {
                    NotebookReferenceLoad.Found(NotebookDocument(root, "Root", items = listOf(
                        NotebookItem("denied", "reference", targetDocumentId = denied),
                        NotebookItem("offline", "reference", targetDocumentId = unavailable),
                    )))
                } else {
                    NotebookReferenceLoad.Unavailable
                }
            },
        )
        val session = NotebookReferenceSession(root, resolver)
        setContent { MaterialTheme { NotebookReferencePreview(session, {}, Modifier.size(900.dp, 640.dp)) } }
        onNodeWithText("Access to this document was denied.").assertIsDisplayed()
        onNodeWithText("Referenced document is currently unavailable.").assertIsDisplayed()
        onAllNodesWithText("Open document").assertCountEquals(1)
        runOnIdle { assertEquals(listOf(root, unavailable), reads) }
    }

    @Test
    fun cycleAndNodeBudgetAreRenderedAsExplicitStubs() = runComposeUiTest {
        val root = NotebookDocumentId("root")
        val resolver = NotebookReferenceResolver(
            authorize = { NotebookReferenceAccess.ALLOWED },
            readAuthorized = { NotebookReferenceLoad.Found(NotebookDocument(root, "Root", items = List(10) {
                NotebookItem("self-$it", "reference", targetDocumentId = root)
            })) },
        )
        val session = NotebookReferenceSession(root, resolver, NotebookReferenceLimits(maxNodes = 3))
        setContent { MaterialTheme { NotebookReferencePreview(session, {}, Modifier.size(360.dp, 640.dp)) } }
        onNodeWithText("Circular reference. This document is already in this branch.").assertIsDisplayed()
        onNodeWithText("Preview size limit reached. Remaining items are omitted.").assertIsDisplayed()
    }

    @Test
    fun loadingCanBeCancelledAndSessionReplacementCancelsTheOldRootJob() = runComposeUiTest {
        val waiting = NotebookReferenceSession(NotebookDocumentId("waiting"), NotebookReferenceResolver(
            authorize = { NotebookReferenceAccess.ALLOWED },
            readAuthorized = { awaitCancellation() },
        ))
        val second = NotebookReferenceSession(NotebookDocumentId("second"), NotebookReferenceResolver(
            authorize = { NotebookReferenceAccess.ALLOWED },
            readAuthorized = { NotebookReferenceLoad.Found(NotebookDocument(it, "Second document")) },
        ))
        val selected = mutableStateOf(waiting)
        setContent { MaterialTheme { NotebookReferencePreview(selected.value, {}, Modifier.size(360.dp, 640.dp)) } }
        onNodeWithText("Loading references…").assertIsDisplayed()
        onNodeWithText("Cancel preview").performClick()
        onNodeWithText("Preview cancelled. Refresh to try again.").assertIsDisplayed()
        onNodeWithText("Refresh preview").performClick()
        onNodeWithText("Loading references…").assertIsDisplayed()
        runOnIdle { selected.value = second }
        onNodeWithText("Second document").assertIsDisplayed()
        runOnIdle { assertEquals(NotebookReferencePhase.CANCELLED, waiting.state.value.phase) }
    }
}
