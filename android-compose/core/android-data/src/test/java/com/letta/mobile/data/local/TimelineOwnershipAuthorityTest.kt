package com.letta.mobile.data.local

import com.letta.mobile.data.timeline.snapshot.TimelineScope
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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

    @Test fun corruptOrOversizedStateNeverDefaultsToLegacy() = runBlocking {
        authority().beginMigration(authority().acquire(scope, TimelineOwnershipAuthority.Route.Legacy))
        val backend = Files.list(temporary.root.toPath()).use { it.findFirst().get() }
        val state = Files.list(backend).use { paths -> paths.filter { it.toString().endsWith(".state") }.findFirst().get() }
        Files.write(state, ByteArray(65537))
        rejected { authority().acquire(scope, TimelineOwnershipAuthority.Route.Legacy) }
        rejected { authority().acquire(scope, TimelineOwnershipAuthority.Route.Canonical) }
    }
}
