package com.letta.mobile.data.local

import com.letta.mobile.data.timeline.snapshot.TimelineScope
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
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
    private val receipt = TimelineOwnershipAuthority.Receipt("source", "generation", 7)

    private suspend fun rejected(block: suspend () -> Unit) {
        try { block(); fail("Expected ownership rejection") } catch (_: IllegalStateException) { }
    }

    /** Same contract as [rejected], but hands the rejection back so a caller can assert on it. */
    private suspend fun rejectionFrom(block: suspend () -> Unit): IllegalStateException {
        try {
            block()
        } catch (rejection: IllegalStateException) {
            return rejection
        }
        throw AssertionError("Expected ownership rejection")
    }

    @Test fun restartFencesOldLeasesAndCanonicalNeverRollsBack() = runBlocking {
        val authority = authority()
        val legacy = authority.acquire(scope, TimelineOwnershipAuthority.Route.Legacy)
        val migration = authority.beginMigration(legacy)
        rejected { authority.withLease(legacy) { fail("legacy writer entered") } }
        rejected { authority().acquire(scope, TimelineOwnershipAuthority.Route.Canonical) }
        assertEquals(TimelineOwnershipAuthority.Phase.Migrating, authority().state(scope).phase)
        authority.prepare(migration) { receipt }
        rejected { authority.withLease(migration) { fail("prepared migration writer entered") } }
        val canonical = authority().commitSwitch(migration) { assertEquals(receipt, it) }
        authority().withLease(canonical) { }
        rejected { authority().acquire(scope, TimelineOwnershipAuthority.Route.Legacy) }
        rejected { authority.abortBeforeSwitch(migration) }
        rejected { authority().acquire(scope.copy(agentId = "other"), TimelineOwnershipAuthority.Route.Canonical) }
        assertEquals(TimelineOwnershipAuthority.Phase.Canonical, authority().state(scope).phase)
    }

    @Test fun secondAuthorityCannotSwitchDuringDatabaseCallback() = runBlocking {
        val legacy = authority().acquire(scope, TimelineOwnershipAuthority.Route.Legacy)
        authority().withLease(legacy) {
            rejected { authority().beginMigration(legacy) }
            rejected { authority().legacyMaintenance(scope.backendId) { fail("maintenance entered") } }
        }
        val migration = authority().beginMigration(legacy)
        assertEquals(legacy.epoch + 1, migration.epoch)
    }

    @Test fun failedValidationAndCancellationKeepResumablePhase() = runBlocking {
        val legacy = authority().acquire(scope, TimelineOwnershipAuthority.Route.Legacy)
        val migration = authority().beginMigration(legacy)
        try {
            authority().prepare(migration) { throw CancellationException("cancel validation") }
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
        assertEquals(TimelineOwnershipAuthority.Phase.Migrating, authority().state(scope).phase)
        authority().prepare(migration) { receipt }
        rejected { authority().commitSwitch(migration) { error("source changed") } }
        assertEquals(TimelineOwnershipAuthority.Phase.Prepared, authority().state(scope).phase)
        val resumed = authority().abortBeforeSwitch(migration)
        assertEquals(migration.epoch + 1, resumed.epoch)
        authority().withLease(resumed) { }
        rejected { authority().withLease(legacy) { fail("stale epoch entered") } }
    }

    @Test fun managedBackendMissingRecordsRequireExplicitRecovery() = runBlocking {
        authority().legacyMaintenance(scope.backendId) { }
        authority().beginMigration(authority().acquire(scope, TimelineOwnershipAuthority.Route.Legacy))
        rejected { authority().legacyMaintenance(scope.backendId) { fail("managed backend pruned") } }
        val other = scope.copy(conversationId = "other")
        rejected { authority().acquire(other, TimelineOwnershipAuthority.Route.Legacy) }
        val backend = Files.list(temporary.root.toPath()).use { it.findFirst().get() }
        val state = Files.list(backend).use { paths -> paths.filter { it.toString().endsWith(".state") }.findFirst().get() }
        Files.delete(state)
        rejected { authority().acquire(scope, TimelineOwnershipAuthority.Route.Legacy) }
    }

    @Test fun mappedIntentRecoversMissingTargetAndKeepsSourcePermanentlyFenced() = runBlocking {
        val target = scope.copy(backendId = "remote-letta:backend")
        val legacy = authority().acquire(scope, TimelineOwnershipAuthority.Route.Legacy)
        val migration = authority().beginMappedMigration(legacy, target) { }
        val mapping = authority().capturedMapping(migration)!!
        assertEquals(scope, mapping.source)
        assertEquals(target, mapping.target)
        assertEquals(legacy.epoch + 1, mapping.sourceEpoch)
        rejected { authority().withLease(legacy) { fail("source writer entered") } }
        rejected { authority().abortBeforeSwitch(migration) }
        // Model death after durable source intent, before target publication.
        fun encodedBackend(value: String): String {
            val bytes = java.io.ByteArrayOutputStream().also { output ->
                java.io.DataOutputStream(output).use { it.writeUTF(value) }
            }.toByteArray()
            return java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        Files.list(temporary.root.toPath().resolve(encodedBackend(target.backendId))).use { paths ->
            paths.filter { it.toString().endsWith(".state") }.forEach { Files.delete(it) }
        }
        assertEquals(migration, authority().beginMappedMigration(legacy, target) { fail("recapture") })
        authority().withLease(migration) {
            rejected { authority().legacyMaintenance(scope.backendId) { fail("source maintenance") } }
        }
        try { authority().prepare(migration) { throw CancellationException("mapped prepare") } }
        catch (_: CancellationException) { }
        assertEquals(TimelineOwnershipAuthority.Phase.Migrating, authority().state(target).phase)
        authority().prepare(migration) { receipt }
        try { authority().commitSwitch(migration) { throw CancellationException("mapped switch") } }
        catch (_: CancellationException) { }
        assertEquals(TimelineOwnershipAuthority.Phase.Prepared, authority().state(target).phase)
        val canonical = authority().commitSwitch(migration) { }
        authority().withLease(canonical) { }
        rejected { authority().acquire(scope, TimelineOwnershipAuthority.Route.Legacy) }
        rejected { authority().beginMappedMigration(legacy, target.copy(backendId = "wrong")) { } }
    }

    @Test fun busyRetryReleasesAttemptAndRechecksLease() = runBlocking {
        val owner = authority()
        val lease = owner.acquire(scope, TimelineOwnershipAuthority.Route.Legacy)
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
        owner.beginMigration(lease)
        attempts = 0
        rejected {
            retryTimelineOwnership {
                attempts++
                owner.withLease(lease) { fail("stale writer entered") }
            }
        }
        assertEquals(1, attempts)
    }

    @Test fun explicitPairAdmissionAllowsAnotherConversationButDoesNotRecreateMappedHistory() = runBlocking {
        val owner = authority()
        val target = scope.copy(backendId = "canonical")
        val legacy = owner.acquire(scope, TimelineOwnershipAuthority.Route.Legacy)
        owner.registerLegacyPair(scope, target) { }
        assertEquals(0L, authority().state(scope).epoch)
        authority().withLease(legacy) { }
        val lease = owner.beginMappedMigration(owner.acquire(scope, TimelineOwnershipAuthority.Route.Legacy), target) { }
        val other = scope.copy(conversationId = "second")
        owner.registerLegacyPair(other, other.copy(backendId = "canonical")) { }
        assertEquals(TimelineOwnershipAuthority.Phase.Legacy, owner.state(other).phase)
        val missing = scope.copy(conversationId = "missing")
        rejected { owner.registerLegacyPair(missing, missing.copy(backendId = "canonical")) { error("existing ledger") } }
        assertEquals(TimelineOwnershipAuthority.Phase.Migrating, owner.state(lease.scope).phase)
        rejected { owner.registerLegacyPair(scope, target.copy(conversationId = "lost")) { fail("mapped source readmitted") } }
    }

    @Test fun corruptOrOversizedStateNeverDefaultsToLegacy() = runBlocking {
        authority().beginMigration(authority().acquire(scope, TimelineOwnershipAuthority.Route.Legacy))
        val backend = Files.list(temporary.root.toPath()).use { it.findFirst().get() }
        val state = Files.list(backend).use { paths -> paths.filter { it.toString().endsWith(".state") }.findFirst().get() }
        Files.write(state, ByteArray(65537))
        // Corruption must fence both routes rather than silently reopening as legacy.
        val legacy = rejectionFrom { authority().acquire(scope, TimelineOwnershipAuthority.Route.Legacy) }
        val canonical = rejectionFrom { authority().acquire(scope, TimelineOwnershipAuthority.Route.Canonical) }
        assertNotNull(legacy.message)
        assertNotNull(canonical.message)
    }
}
