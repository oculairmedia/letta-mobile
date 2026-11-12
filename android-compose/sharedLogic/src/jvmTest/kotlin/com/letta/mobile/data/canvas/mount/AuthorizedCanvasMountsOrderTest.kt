package com.letta.mobile.data.canvas.mount

import com.letta.mobile.data.model.CanvasId
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.util.Collections
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Proves the fixed check order with a storage double: input, scope, proposal, storage, winner. */
class AuthorizedCanvasMountsOrderTest {
    private val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val winner = CanvasId("winner-board")
    private val proposal = CanvasId("proposal-board")

    private fun mounts(storage: RecordingStorage, authority: CanvasMountAuthority = TableAuthority(Answer.ALLOW)) =
        AuthorizedCanvasMounts(storage, RecordingAuthority(log, authority))

    @Test
    fun readAsksScopeBeforeStorageAndWinnerBeforeReturning() = runBlocking {
        val result = mounts(RecordingStorage(log, stored = winner)).read(bindingRequest("a", "c"))
        assertEquals(CanvasMountResult.Found(winner), result)
        assertEquals(listOf("address", "store.readBinding", "see:winner-board"), log)
        log.clear()
        assertEquals(CanvasMountResult.Found(winner), mounts(RecordingStorage(log, stored = winner)).read(defaultRequest("a")))
        assertEquals(listOf("address", "store.readDefault", "see:winner-board"), log)
    }

    @Test
    fun absentReadIsNoneOnlyAfterScopeAndNeverAsksAboutAWinner() = runBlocking {
        assertEquals(CanvasMountResult.None, mounts(RecordingStorage(log)).read(bindingRequest("a", "c")))
        assertEquals(listOf("address", "store.readBinding"), log)
    }

    @Test
    fun deniedScopeNeverTouchesStorageOrAsksFurther() = runBlocking {
        val storage = RecordingStorage(log, stored = winner)
        val denied = mounts(storage, TableAuthority(Answer.DENY))
        assertEquals(CanvasMountResult.Denied, denied.read(bindingRequest("a", "c")))
        assertEquals(CanvasMountResult.Denied, denied.read(defaultRequest("a")))
        assertEquals(CanvasMountResult.Denied, denied.bindIfAbsent(bindingRequest("a", "c"), proposal))
        assertEquals(CanvasMountResult.Denied, denied.replaceBinding(bindingRequest("a", "c"), proposal))
        assertEquals(CanvasMountResult.Denied, denied.setDefault(defaultRequest("a"), proposal))
        assertEquals(List(5) { "address" }, log)
        assertEquals(winner, storage.stored)
    }

    @Test
    fun bindAsksProposalBeforeMutationAndWinnerBeforeReturning() = runBlocking {
        val result = mounts(RecordingStorage(log, stored = winner)).bindIfAbsent(bindingRequest("a", "c"), proposal)
        assertEquals(CanvasMountResult.Bound(winner), result)
        assertEquals(listOf("address", "propose:proposal-board", "store.bindIfAbsent", "see:winner-board"), log)
    }

    @Test
    fun deniedProposalNeverReachesStorage() = runBlocking {
        val storage = RecordingStorage(log)
        val denied = mounts(storage, TableAuthority(Answer.ALLOW, proposal = Answer.DENY))
        assertEquals(CanvasMountResult.Denied, denied.bindIfAbsent(bindingRequest("a", "c"), proposal))
        assertEquals(CanvasMountResult.Denied, denied.replaceBinding(bindingRequest("a", "c"), proposal))
        assertEquals(CanvasMountResult.Denied, denied.setDefault(defaultRequest("a"), proposal))
        assertEquals(List(3) { listOf("address", "propose:proposal-board") }.flatten(), log)
        assertNull(storage.stored)
    }

    @Test
    fun replaceAndSetReturnTheCallersOwnCanvasWithoutAWinnerQuestion() = runBlocking {
        val storage = RecordingStorage(log, stored = winner)
        assertEquals(CanvasMountResult.Replaced(proposal), mounts(storage).replaceBinding(bindingRequest("a", "c"), proposal))
        assertEquals(CanvasMountResult.DefaultSet(proposal), mounts(storage).setDefault(defaultRequest("a"), proposal))
        assertEquals(
            listOf("address", "propose:proposal-board", "store.replaceBinding", "address", "propose:proposal-board", "store.setDefault"),
            log,
        )
    }

    @Test
    fun overLongOrBlankIdsAreRejectedBeforeAuthorityOrStorage() = runBlocking {
        val mounts = mounts(RecordingStorage(log, stored = winner))
        val long = "x".repeat(AuthorizedCanvasMounts.MAX_ID_BYTES + 1)
        val wide = "é".repeat(AuthorizedCanvasMounts.MAX_ID_BYTES / 2 + 1)
        assertEquals(CanvasMountResult.InvalidInput, mounts.read(bindingRequest(long, "c")))
        assertEquals(CanvasMountResult.InvalidInput, mounts.read(bindingRequest("a", wide)))
        assertEquals(CanvasMountResult.InvalidInput, mounts.read(defaultRequest(" ")))
        assertEquals(CanvasMountResult.InvalidInput, mounts.bindIfAbsent(bindingRequest("a", ""), proposal))
        assertEquals(CanvasMountResult.InvalidInput, mounts.bindIfAbsent(bindingRequest("a", "c"), CanvasId(long)))
        assertEquals(CanvasMountResult.InvalidInput, mounts.replaceBinding(bindingRequest("a", "c"), CanvasId(" ")))
        assertEquals(CanvasMountResult.InvalidInput, mounts.setDefault(defaultRequest(long), proposal))
        assertTrue(log.isEmpty(), "Nothing may be asked or touched: $log")
        val exact = "x".repeat(AuthorizedCanvasMounts.MAX_ID_BYTES)
        assertEquals(CanvasMountResult.Found(winner), mounts.read(bindingRequest(exact, exact)))
    }

    @Test
    fun principalsComeOnlyFromBoundedVerifiedIdentityAndNeverPrintIt() {
        assertNull(CanvasMountPrincipal.fromVerifiedConnection(""))
        assertNull(CanvasMountPrincipal.fromVerifiedConnection("x".repeat(AuthorizedCanvasMounts.MAX_ID_BYTES + 1)))
        val principal = principal("endpoint-secret")
        assertEquals("endpoint-secret", principal.endpoint)
        assertFalse(principal.toString().contains("endpoint-secret"))
        assertFalse(bindingRequest("a", "c", "endpoint-secret").toString().contains("endpoint-secret"))
    }

    @Test
    fun storageExceptionsBecomeFixedReasonsWithoutTheirMessages() = runBlocking {
        val cases = mapOf(
            AccessDeniedException("/secret/product-state/canvas-mounts/binding-abc.json") to CanvasMountFailure.IO_FAILURE,
            IOException("/secret/path") to CanvasMountFailure.IO_FAILURE,
            IllegalArgumentException("secret corrupt detail") to CanvasMountFailure.INVALID_STATE,
            UnsupportedOperationException("secret sync detail") to CanvasMountFailure.UNSUPPORTED,
            NoSuchElementException("secret other detail") to CanvasMountFailure.UNEXPECTED,
        )
        for ((failure, reason) in cases) {
            val mounts = mounts(RecordingStorage(log, failure = failure))
            val results = listOf(
                mounts.read(bindingRequest("a", "c")),
                mounts.bindIfAbsent(bindingRequest("a", "c"), proposal),
                mounts.replaceBinding(bindingRequest("a", "c"), proposal),
                mounts.setDefault(defaultRequest("a"), proposal),
            )
            results.forEach { result ->
                assertEquals(CanvasMountResult.StorageFailure(reason), result)
                assertFalse(result.toString().contains("secret"), result.toString())
            }
        }
    }

    @Test
    fun aFailingAuthorityDeniesButCancellationPropagates(): Unit = runBlocking {
        val storage = RecordingStorage(log, stored = winner)
        assertEquals(CanvasMountResult.Denied, mounts(storage, TableAuthority(Answer.THROW)).read(bindingRequest("a", "c")))
        assertEquals(CanvasMountResult.Denied, mounts(storage, TableAuthority(Answer.ABSTAIN)).read(bindingRequest("a", "c")))
        val winnerThrows = mounts(storage, TableAuthority(Answer.ALLOW, winner = Answer.THROW))
        assertEquals(CanvasMountResult.Denied, winnerThrows.read(bindingRequest("a", "c")))
        val cancelling = object : CanvasMountAuthority by TableAuthority(Answer.ALLOW) {
            override suspend fun mayAddress(request: CanvasMountRequest<CanvasMountScope>): CanvasMountDecision =
                throw CancellationException("cancelled while deciding")
        }
        assertFailsWith<CancellationException> { mounts(storage, cancelling).read(bindingRequest("a", "c")) }
    }

    @Test
    fun storageCancellationPropagatesInsteadOfBecomingAFailure() = runBlocking {
        val throwing = mounts(RecordingStorage(log, failure = CancellationException("cancelled in storage")))
        assertFailsWith<CancellationException> { throwing.bindIfAbsent(bindingRequest("a", "c"), proposal) }
        val storage = RecordingStorage(log, suspendForever = true)
        var outcome: CanvasMountResult? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            outcome = mounts(storage).read(bindingRequest("a", "c"))
        }
        assertTrue("store.readBinding" in log, "The call must be suspended inside storage: $log")
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertNull(outcome, "A cancelled call must not produce a result")
    }
}
