package com.letta.mobile.data.canvas.references

import com.letta.mobile.data.canvas.NotebookDocument
import com.letta.mobile.data.canvas.NotebookDocumentId
import com.letta.mobile.data.canvas.NotebookItem
import com.letta.mobile.data.canvas.references.NotebookReferenceContent.Placeholder
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NotebookReferenceSessionTest {
    private val a = NotebookDocumentId("a")
    private val b = NotebookDocumentId("b")

    @Test
    fun selfCycleIsAnExplicitStubWithoutAnotherLookup() = runTest {
        val source = ReferenceSource(NotebookDocument(a, "A", items = listOf(reference("self", a))))
        val root = source.session(a).loaded()
        assertEquals(Placeholder.CYCLE, root.children.single().content)
        assertEquals(listOf(a, a), root.children.single().path)
        assertEquals(listOf(a), source.reads)
    }

    @Test
    fun mutualCycleStopsAtTheAncestorAndKeepsImmutablePaths() = runTest {
        val source = ReferenceSource(
            NotebookDocument(a, "A", items = listOf(reference("to-b", b))),
            NotebookDocument(b, "B", items = listOf(reference("to-a", a))),
        )
        val root = source.session(a).loaded()
        val child = root.children.single()
        assertEquals(Placeholder.CYCLE, child.children.single().content)
        assertEquals(listOf(a), root.path)
        assertEquals(listOf(a, b), child.path)
        assertEquals(listOf(a, b, a), child.children.single().path)
        val extended = child.path.adding(NotebookDocumentId("another"))
        assertEquals(3, extended.size)
        assertEquals(listOf(a, b), child.path)
        assertEquals(listOf(a, b), source.reads)
    }

    @Test
    fun deepChainUsesAnExplicitSafeMaximumWithoutReadingPastIt() = runTest {
        val reads = mutableListOf<NotebookDocumentId>()
        val resolver = NotebookReferenceResolver(
            authorize = { NotebookReferenceAccess.ALLOWED },
            readAuthorized = { id ->
                reads.add(id)
                val next = NotebookDocumentId((id.value.toInt() + 1).toString())
                NotebookReferenceLoad.Found(NotebookDocument(id, id.value, items = listOf(reference("next", next))))
            },
        )
        val root = NotebookReferenceSession(NotebookDocumentId("0"), resolver, NotebookReferenceLimits(maxDepth = 3)).loaded()
        val nodes = flatten(root)
        assertEquals(5, nodes.size)
        assertEquals(Placeholder.DEPTH_LIMIT, nodes.last().content)
        assertEquals(listOf("0", "1", "2", "3"), reads.map { it.value })
        assertFailsWith<IllegalArgumentException> { NotebookReferenceLimits(maxDepth = 33) }
        assertFailsWith<IllegalArgumentException> { NotebookReferenceLimits(maxNodes = 513) }
        assertFailsWith<IllegalArgumentException> { NotebookReferenceLimits(maxNodes = 0) }
    }

    @Test
    fun wideListCountsPlaceholdersAndStopsInspectingItemsAtTheNodeBudget() = runTest {
        var inspected = 0
        val hugeItems = object : AbstractList<NotebookItem>() {
            override val size: Int = 1_000_000
            override fun get(index: Int): NotebookItem {
                inspected++
                check(inspected <= 4) { "Traversal continued after the cap" }
                return reference("item-$index", b)
            }
        }
        val source = ReferenceSource(NotebookDocument(a, "A", items = hugeItems), NotebookDocument(b, "B"))
        val root = source.session(a, NotebookReferenceLimits(maxNodes = 5)).loaded()
        assertEquals(5, flatten(root).size)
        assertEquals(4, inspected)
        assertEquals(Placeholder.NODE_LIMIT, root.children.last().content)
        assertEquals(listOf(a, b, b, b), source.reads)
        val cutoff = source.session(a, NotebookReferenceLimits(maxNodes = 1)).loaded()
        assertEquals(Placeholder.NODE_LIMIT, cutoff.content)
        assertTrue(cutoff.children.isEmpty())
        assertEquals(4, inspected)
    }

    @Test
    fun nestedWideBranchesShareOneTotalBudgetIncludingEveryStub() = runTest {
        val source = ReferenceSource(
            NotebookDocument(a, "A", items = List(20) { reference("outer-$it", b) }),
            NotebookDocument(b, "B", items = List(20) { reference("inner-$it", a) }),
        )
        val nodes = flatten(source.session(a, NotebookReferenceLimits(maxNodes = 7)).loaded())
        assertEquals(7, nodes.size)
        assertEquals(Placeholder.NODE_LIMIT, nodes.last().content)
        assertTrue(nodes.count { it.content == Placeholder.CYCLE } > 0)
        assertEquals(listOf(a, b), source.reads)
    }

    @Test
    fun siblingRepeatIsExpandedAndAuthorizedSeparatelyWithoutGlobalVisitedState() = runTest {
        val source = ReferenceSource(
            NotebookDocument(a, "A", items = listOf(reference("first", b), reference("second", b))),
            NotebookDocument(b, "B", "**shared Markdown**"),
        )
        val root = source.session(a).loaded()
        assertEquals(2, root.children.size)
        root.children.forEach { child ->
            assertIs<NotebookReferenceContent.Markdown>(child.content)
            assertEquals(persistentListOf(a, b), child.path)
            assertEquals(b, child.targetId)
        }
        assertEquals(listOf(a, b, b), source.authorizations)
        assertEquals(source.authorizations, source.reads)
        assertEquals(listOf(a), root.path)
    }

    @Test
    fun denyNoReadGateRunsBeforeStorageForEachTarget() = runTest {
        val source = ReferenceSource(
            NotebookDocument(a, "A", items = listOf(reference("denied", b))),
            NotebookDocument(b, "secret", "must never be read"),
        )
        source.access[b] = NotebookReferenceAccess.DENIED
        val root = source.session(a).loaded()
        assertEquals(Placeholder.DENIED, root.children.single().content)
        assertEquals(listOf(a, b), source.authorizations)
        assertEquals(listOf(a), source.reads, "the production resolver gate must not call storage for a denied ID")
        source.access[a] = NotebookReferenceAccess.DENIED
        assertEquals(Placeholder.DENIED, source.session(a).loaded().content)
        assertEquals(listOf(a), source.reads)
    }

    @Test
    fun unavailableAuthorityDoesNotReadAndMissingAndFailedTargetsRemainDistinct() = runTest {
        var storageCalls = 0
        val resolver = NotebookReferenceResolver(
            authorize = { NotebookReferenceAccess.UNAVAILABLE },
            readAuthorized = { storageCalls++; error("must not read") },
        )
        assertEquals(Placeholder.UNAVAILABLE, NotebookReferenceSession(a, resolver).loaded().content)
        assertEquals(0, storageCalls)
        val missing = ReferenceSource()
        assertEquals(Placeholder.MISSING, missing.session(a).loaded().content)
        val failing = NotebookReferenceResolver(
            authorize = { NotebookReferenceAccess.ALLOWED },
            readAuthorized = { error("source offline") },
        )
        assertEquals(Placeholder.UNAVAILABLE, NotebookReferenceSession(a, failing).loaded().content)
    }

    @Test
    fun identityIsTheExactStoredTypedIdAndMismatchedStorageCannotSupplyPaintData() = runTest {
        val storedId = NotebookDocumentId("00112233445566778899aabbccddeeff")
        val source = ReferenceSource(NotebookDocument(storedId, "Existing note", "# original"))
        val root = source.session(storedId).loaded()
        assertSame(storedId, root.targetId)
        assertEquals(NotebookReferenceContent.Markdown("Existing note", "# original"), root.content)
        val wrong = NotebookReferenceResolver(
            authorize = { NotebookReferenceAccess.ALLOWED },
            readAuthorized = { NotebookReferenceLoad.Found(NotebookDocument(b, "wrong", "never paint")) },
        )
        assertEquals(Placeholder.UNAVAILABLE, NotebookReferenceSession(a, wrong).loaded().content)
    }

    @Test
    fun unknownPreservationRetainsOpaqueDataAndDoesNotFollowItsTarget() = runTest {
        val opaque = NotebookItem("future", "vendor.future", 19, b, "independent-chat", """{"opaque":[1,{"keep":true}]}""")
        val futureReference = reference("new-reference-version", b).copy(version = 2)
        val invalid = NotebookItem("invalid", "reference")
        val original = NotebookDocument(a, "A", items = listOf(opaque, futureReference, invalid))
        val source = ReferenceSource(original)
        val children = source.session(a).loaded().children
        assertEquals(
            listOf(Placeholder.UNSUPPORTED_ITEM, Placeholder.UNSUPPORTED_ITEM, Placeholder.INVALID_REFERENCE),
            children.map { it.content },
        )
        assertSame(opaque, children.first().item)
        assertEquals(original.items, children.map { it.item })
        assertEquals(original, source.documents[a])
        assertEquals(listOf(a), source.reads)
    }

    @Test
    fun refreshWithdrawsOldContentWhileLoadingAndRechecksAuthority() = runTest {
        val source = ReferenceSource(NotebookDocument(a, "A", "old"))
        val release = CompletableDeferred<Unit>()
        var suspended = false
        val resolver = NotebookReferenceResolver(
            authorize = {
                if (suspended) {
                    release.await()
                }
                source.access[it] ?: NotebookReferenceAccess.ALLOWED
            },
            readAuthorized = { NotebookReferenceLoad.Found(source.documents.getValue(it)) },
        )
        val session = NotebookReferenceSession(a, resolver)
        assertEquals(NotebookReferencePhase.IDLE, session.state.value.phase)
        session.loaded()
        suspended = true
        source.access[a] = NotebookReferenceAccess.DENIED
        val refresh = launch { session.refresh() }
        runCurrent()
        assertEquals(NotebookReferencePhase.LOADING, session.state.value.phase)
        assertNull(session.state.value.root)
        release.complete(Unit)
        refresh.join()
        assertEquals(Placeholder.DENIED, assertNotNull(session.state.value.root).content)
    }

    @Test
    fun explicitCancelAndCallerCancellationPropagateAndClearLoading() = runTest {
        val resolver = NotebookReferenceResolver(
            authorize = { NotebookReferenceAccess.ALLOWED },
            readAuthorized = { awaitCancellation() },
        )
        val session = NotebookReferenceSession(a, resolver)
        var cancellations = 0
        val first = launch {
            try { session.refresh() } catch (cancelled: CancellationException) { cancellations++; throw cancelled }
        }
        runCurrent()
        session.cancel()
        first.join()
        assertEquals(1, cancellations)
        assertEquals(NotebookReferencePhase.CANCELLED, session.state.value.phase)
        assertNull(session.state.value.root)
        val second = launch {
            try { session.refresh() } catch (cancelled: CancellationException) { cancellations++; throw cancelled }
        }
        runCurrent()
        second.cancel()
        second.join()
        assertEquals(2, cancellations)
        assertEquals(NotebookReferencePhase.CANCELLED, session.state.value.phase)
    }

    @Test
    fun newerRefreshSupersedesSuspendedWorkWithoutPublishingItsResult() = runTest {
        var reads = 0
        val resolver = NotebookReferenceResolver(
            authorize = { NotebookReferenceAccess.ALLOWED },
            readAuthorized = {
                reads++
                if (reads == 1) awaitCancellation()
                NotebookReferenceLoad.Found(NotebookDocument(it, "new", "latest"))
            },
        )
        val session = NotebookReferenceSession(a, resolver)
        val first = launch { session.refresh() }
        runCurrent()
        session.refresh()
        first.join()
        assertTrue(first.isCancelled)
        assertEquals(NotebookReferencePhase.READY, session.state.value.phase)
        assertEquals(NotebookReferenceContent.Markdown("new", "latest"), assertNotNull(session.state.value.root).content)
    }
}

private fun reference(key: String, target: NotebookDocumentId): NotebookItem =
    NotebookItem(key, "reference", targetDocumentId = target)

private suspend fun NotebookReferenceSession.loaded(): NotebookReferenceNode {
    refresh()
    assertEquals(NotebookReferencePhase.READY, state.value.phase)
    return assertNotNull(state.value.root)
}

private fun flatten(root: NotebookReferenceNode): List<NotebookReferenceNode> =
    listOf(root) + root.children.flatMap(::flatten)

/** Fixture only: its explicit decisions exercise ordering in the production resolver, not host security policy. */
private class ReferenceSource(vararg initial: NotebookDocument) {
    val documents = initial.associateBy { it.id }.toMutableMap()
    val authorizations = mutableListOf<NotebookDocumentId>()
    val reads = mutableListOf<NotebookDocumentId>()
    val access = mutableMapOf<NotebookDocumentId, NotebookReferenceAccess>()
    private val resolver = NotebookReferenceResolver(
        authorize = { id -> authorizations.add(id); access[id] ?: NotebookReferenceAccess.ALLOWED },
        readAuthorized = { id ->
            reads.add(id)
            documents[id]?.let { NotebookReferenceLoad.Found(it) } ?: NotebookReferenceLoad.Missing
        },
    )

    fun session(id: NotebookDocumentId, limits: NotebookReferenceLimits = NotebookReferenceLimits()): NotebookReferenceSession =
        NotebookReferenceSession(id, resolver, limits)
}
