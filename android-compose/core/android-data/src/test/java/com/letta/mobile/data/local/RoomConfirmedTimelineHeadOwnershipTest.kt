package com.letta.mobile.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.letta.mobile.data.timeline.snapshot.ConfirmedTimelineReadResult
import com.letta.mobile.data.timeline.snapshot.SnapshotReadFailure
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEnvelope
import com.letta.mobile.data.timeline.snapshot.StoredTimelineEvent
import com.letta.mobile.data.timeline.snapshot.TimelineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * letta-mobile-grrhq: a conversation's canonical snapshot must not change owning
 * agent through a revision race.
 *
 * The head row is keyed by (backendId, conversationId) with NO agent in the key,
 * but reads validate it with `matches(scope)`, which requires the agent to match.
 * That left ownership unguarded on the WRITE side: whichever holder reached the
 * higher revision rewrote `agent_id` and took the conversation, after which the
 * previous owner's reads fail `matches` and it reconciles its entire timeline
 * from scratch.
 *
 * In the live split that motivated this (parent `agent-c356b54a...` vs child
 * `agent-597b5756...` on `local-conv-190`) the child was at revision 1-2 against
 * a parent at ~7623, so the pre-existing stale-revision check happened to shield
 * the parent. That is luck, not a guarantee: on a short conversation, a fresh
 * install, or after a cache clear, the competing holder wins the race.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class RoomConfirmedTimelineHeadOwnershipTest {
    private var database: LettaDatabase? = null

    @After
    fun tearDown() {
        database?.close()
        database = null
    }

    private fun store(): RoomConfirmedTimelineStore {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, LettaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
            .also { database = it }
        return RoomConfirmedTimelineStore(db)
    }

    private fun envelope(scope: TimelineScope, revision: Long) = StoredTimelineEnvelope(
        schemaVersion = 1,
        scope = scope,
        revision = revision,
        liveCursor = "srv-$revision",
        backfillCursor = "srv-1",
        releasedOlderCount = 0,
        events = listOf(
            StoredTimelineEvent(
                position = 1.0,
                otid = "otid-$revision",
                content = "revision $revision",
                serverId = "srv-$revision",
                messageType = "USER",
                dateIso = "2026-08-31T22:27:47Z",
            )
        ),
        writtenAtMillis = 1_000L + revision,
    )

    private val parent = TimelineScope(
        backendId = "b1",
        conversationId = "local-conv-190",
        agentId = "agent-c356b54a",
    )
    private val child = TimelineScope(
        backendId = "b1",
        conversationId = "local-conv-190",
        agentId = "agent-597b5756",
    )

    @Test
    fun aCompetingAgentCannotTakeOwnershipByWinningTheRevisionRace() = runTest {
        val store = store()
        // Parent owns the conversation, but at a LOW revision — the case the
        // stale-revision check does not cover.
        assertTrue(store.writeSnapshot(envelope(parent, revision = 2L)))

        // Child holder, started from scratch after METADATA_INVALID, reaches a
        // higher revision first.
        assertFalse(
            "a different scoped agent must not be able to take the head",
            store.writeSnapshot(envelope(child, revision = 9L)),
        )

        // The parent still owns and can still read its own timeline.
        val read = store.readSnapshotResult(parent)
        assertTrue(
            "parent must not be evicted from its own conversation",
            read is ConfirmedTimelineReadResult.Active,
        )
        assertEquals(2L, store.readSnapshot(parent)?.revision)
    }

    @Test
    fun theEvictedOwnerWouldOtherwiseLoseItsWholeTimeline() = runTest {
        val store = store()
        assertTrue(store.writeSnapshot(envelope(parent, revision = 2L)))
        store.writeSnapshot(envelope(child, revision = 9L))

        // Guards the actual user-visible consequence: before the fix the parent's
        // read here returned ReconciliationRequired(METADATA_INVALID) and the
        // parent rebuilt its entire timeline.
        val read = store.readSnapshotResult(parent)
        assertFalse(
            "parent read must not degrade to METADATA_INVALID",
            read is ConfirmedTimelineReadResult.ReconciliationRequired &&
                read.failure == SnapshotReadFailure.METADATA_INVALID,
        )
    }

    @Test
    fun theOwningAgentCanStillAdvanceItsOwnHead() = runTest {
        val store = store()
        assertTrue(store.writeSnapshot(envelope(parent, revision = 2L)))
        assertTrue(store.writeSnapshot(envelope(parent, revision = 3L)))
        assertEquals(3L, store.readSnapshot(parent)?.revision)
    }

    @Test
    fun anUnscopedHeadCanStillBePromotedToAScopedAgent() = runTest {
        val store = store()
        val unscoped = TimelineScope(backendId = "b1", conversationId = "local-conv-190")
        assertTrue(store.writeSnapshot(envelope(unscoped, revision = 2L)))

        // Promotion (null -> scoped) mirrors the loop-cache promotion in
        // getAliasedLoopLocked and must keep working.
        assertTrue(store.writeSnapshot(envelope(parent, revision = 3L)))
        assertNotNull(store.readSnapshot(parent))
        assertEquals(3L, store.readSnapshot(parent)?.revision)
    }

    @Test
    fun anUnscopedWriterDoesNotDowngradeAScopedHead() = runTest {
        val store = store()
        assertTrue(store.writeSnapshot(envelope(parent, revision = 2L)))

        val unscoped = TimelineScope(backendId = "b1", conversationId = "local-conv-190")
        assertFalse(
            "an unscoped write must not take a scoped head",
            store.writeSnapshot(envelope(unscoped, revision = 3L)),
        )

        // Preserving only the head's agent_id is not enough: the head would then
        // point at a manifest written under a DIFFERENT agent, and the owner's
        // read fails on the manifest instead. Refusing the write keeps head and
        // manifest agreeing on the agent.
        assertNotNull(
            "scoped owner must survive an unscoped write",
            store.readSnapshot(parent),
        )
        assertEquals(2L, store.readSnapshot(parent)?.revision)
    }

    @Test
    fun distinctConversationsRemainIndependent() = runTest {
        val store = store()
        val otherConversation = TimelineScope(
            backendId = "b1",
            conversationId = "local-conv-191",
            agentId = "agent-597b5756",
        )
        assertTrue(store.writeSnapshot(envelope(parent, revision = 2L)))
        assertTrue(
            "the guard is per-conversation, not a global agent lock",
            store.writeSnapshot(envelope(otherConversation, revision = 9L)),
        )
    }
}
