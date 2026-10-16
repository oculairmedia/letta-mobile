package com.letta.mobile.data.local

import com.letta.mobile.data.timeline.snapshot.TimelineScope
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TimelineOwnershipAuthorityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val scope = TimelineScope("backend", "conversation", "agent")
    private fun authority() = TimelineOwnershipAuthority(temporary.root.toPath())

    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Expected ownership rejection") } catch (_: IllegalStateException) { }
    }

    private fun hash(value: String): String {
        val bytes = ByteArrayOutputStream().also { output -> DataOutputStream(output).use { it.writeUTF(value) } }.toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    /**
     * Writes a record the way an older build's migration did: a mapped target left mid-migration.
     * The byte layout is the authority's version-2 format (digest, then payload).
     */
    private fun writeOlderMigratingRecord(target: TimelineScope, epoch: Long) {
        val source = target.copy(backendId = "legacy-config-id")
        val payload = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use { out ->
                fun scope(value: TimelineScope) {
                    out.writeUTF(value.backendId); out.writeUTF(value.conversationId)
                    out.writeBoolean(value.agentId != null); value.agentId?.let { out.writeUTF(it) }
                }
                out.writeInt(2)
                scope(target)
                out.writeLong(epoch); out.writeUTF("Migrating"); out.writeBoolean(false)
                out.writeBoolean(true)
                scope(source); out.writeLong(1); scope(target); out.writeLong(epoch)
            }
        }.toByteArray()
        val backend = temporary.root.toPath().resolve(hash(target.backendId))
        Files.createDirectories(backend)
        Files.write(backend.resolve("managed"), ByteArray(0))
        Files.write(backend.resolve(hash(target.conversationId) + ".state"), MessageDigest.getInstance("SHA-256").digest(payload) + payload)
    }

    /**
     * letta-mobile-by4vm.15: canonical is the only route. An unclaimed conversation and one a managed
     * backend has never seen open canonical without a copy; a canonical one reopens at its own epoch,
     * and the agent pin still fences other agents.
     */
    @Test fun claimOpensEveryScopeCanonicalAndReopensAtTheSameEpoch() = runBlocking {
        val authority = authority()
        val fresh = authority.claimCanonical(scope)
        assertEquals(TimelineOwnershipAuthority.Route.Canonical, fresh.route)
        assertEquals(TimelineOwnershipAuthority.Phase.Canonical, authority().state(scope).phase)
        authority.withLease(fresh) { }
        assertEquals(fresh, authority().claimCanonical(scope))

        val unseen = scope.copy(conversationId = "unseen-in-managed-backend")
        authority.withLease(authority.claimCanonical(unseen)) { }

        rejected { authority.claimCanonical(scope.copy(agentId = "other")) }
    }

    /** A record an older build left mid-migration decodes and is claimed past its epoch, without its source. */
    @Test fun anOlderBuildsInterruptedMigrationIsClaimedCanonical() = runBlocking {
        writeOlderMigratingRecord(scope, epoch = 3)
        assertEquals(TimelineOwnershipAuthority.Phase.Migrating, authority().state(scope).phase)
        val stale = TimelineOwnershipAuthority.Lease(scope, 3, TimelineOwnershipAuthority.Route.Canonical)
        rejected { authority().withLease(stale) { fail("migrating record admitted a canonical writer") } }

        val claimed = authority().claimCanonical(scope)
        assertEquals(4L, claimed.epoch)
        authority().withLease(claimed) { }
        rejected { authority().withLease(stale) { fail("stale epoch entered") } }
    }

    /**
     * Opening a conversation for the first time in a backend that already manages others is
     * ordinary traffic, and the owner lookup must answer "unclaimed" rather than demanding
     * recovery the way a state read does - it used to crash the app on a new conversation.
     */
    @Test fun ownerLookupTreatsAnUnclaimedConversationAsUnowned() = runBlocking {
        val authority = authority()
        authority.claimCanonical(scope)
        assertEquals("agent", authority.ownerAgent(scope))
        val fresh = scope.copy(conversationId = "second-conversation", agentId = "other")
        assertNull(authority.ownerAgent(fresh))
        rejected { authority.state(fresh) }
    }

    @Test fun busyRetryReleasesAttemptAndRechecksLease() = runBlocking {
        val owner = authority()
        val lease = owner.claimCanonical(scope)
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val holder = kotlinx.coroutines.CoroutineScope(coroutineContext).launch {
            owner.withLease(lease) { entered.complete(Unit); release.await() }
        }
        entered.await()
        var attempts = 0
        retryTimelineOwnership {
            attempts++
            try {
                authority().withLease(lease) { }
            } catch (busy: TimelineOwnershipBusyException) {
                release.complete(Unit)
                holder.join()
                throw busy
            }
        }
        assertEquals(2, attempts)
    }

    /**
     * letta-mobile-gdfrn: every guarded timeline read on chat open acquires the authority, and each
     * acquisition used to fsync the authority directory. Reads must not sync anything; a publication
     * still syncs the authority directory (the backend entry) before the backend's own files.
     */
    @Test fun readOnlyAcquisitionNeverSyncsButPublicationSyncsParentFirst() = runBlocking {
        val synced = mutableListOf<java.nio.file.Path>()
        val root = temporary.root.toPath()
        val counting = TimelineOwnershipAuthority(root) { synced.add(it) }

        val lease = counting.claimCanonical(scope)
        val published = synced.toList()
        assertEquals("publication syncs the authority directory first", root, published.first())
        assertTrue("and then the backend directory", published.drop(1).isNotEmpty() && published.drop(1).all { it.parent == root })

        synced.clear()
        repeat(3) {
            counting.state(scope)
            counting.ownerAgent(scope)
            counting.claimCanonical(scope)
            counting.withLease(lease) { }
        }
        assertEquals("read-only acquisitions must not fsync", emptyList<java.nio.file.Path>(), synced)
    }

    @Test fun corruptOrOversizedStateFailsClosed() = runBlocking {
        authority().claimCanonical(scope)
        val backend = Files.list(temporary.root.toPath()).use { it.findFirst().get() }
        val state = Files.list(backend).use { paths -> paths.filter { it.toString().endsWith(".state") }.findFirst().get() }
        Files.write(state, ByteArray(65537))
        // Corruption must fence the route rather than silently reclaiming the conversation.
        val claim = runCatching { authority().claimCanonical(scope) }.exceptionOrNull()
        val owner = runCatching { authority().ownerAgent(scope) }.exceptionOrNull()
        assertTrue("claim reopened a corrupt record: $claim", claim is IllegalStateException)
        assertTrue("owner lookup read a corrupt record: $owner", owner is IllegalStateException)
    }
}
