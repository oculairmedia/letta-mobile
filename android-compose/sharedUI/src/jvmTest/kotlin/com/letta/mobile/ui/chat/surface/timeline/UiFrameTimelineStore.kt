package com.letta.mobile.ui.chat.surface.timeline

import com.letta.mobile.data.timeline.TimelineBoundedStore
import com.letta.mobile.data.timeline.TimelineStoreReader
import com.letta.mobile.data.timeline.TimelineStoreTransaction
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.delay
import java.util.concurrent.CopyOnWriteArrayList

/**
 * letta-mobile-29sxj: an in-memory [TimelineBoundedStore] for the frame harness. sharedLogic's own
 * in-memory store is internal to that module's tests, so the sharedUI tests cannot reach it.
 *
 * It holds ONE conversation (the harness opens one scope). A transaction that throws restores what
 * it started from. [readLatencyMillis] is the real time every read takes, as a device database
 * does, so Paging's loading states span Compose frames instead of collapsing into one. Anything a
 * read or transaction throws is kept in [failures], so a harness that stalls can say why.
 */
internal class UiFrameTimelineStore(private val readLatencyMillis: Long = 0) : TimelineBoundedStore {
    private val ledger = UiFrameLedger()
    private val recorded = CopyOnWriteArrayList<Throwable>()

    val failures: List<Throwable> get() = recorded.toList()

    override suspend fun <T> read(scope: TimelineScope, block: suspend TimelineStoreReader.() -> T): T {
        if (readLatencyMillis > 0) delay(readLatencyMillis)
        return remembering { block(UiFrameReader(ledger)) }
    }

    override suspend fun <T> transaction(scope: TimelineScope, block: suspend TimelineStoreTransaction.() -> T): T {
        val before = ledger.save()
        return remembering(onFailure = { ledger.restore(before) }) { block(UiFrameTransaction(ledger)) }
    }

    private suspend fun <T> remembering(onFailure: () -> Unit = {}, body: suspend () -> T): T = try {
        body()
    } catch (failure: Throwable) {
        recorded.add(failure)
        onFailure()
        throw failure
    }
}
