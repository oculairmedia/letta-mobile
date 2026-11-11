package com.letta.mobile.plugin.testkit.keystore

import com.letta.mobile.plugin.keystore.HealthStatus
import com.letta.mobile.plugin.keystore.KeystoreError
import com.letta.mobile.plugin.keystore.KeystoreErrorCode
import com.letta.mobile.plugin.keystore.KeystoreOperation
import com.letta.mobile.plugin.keystore.SecretValue

/** Descriptor, locking, readiness and fail-closed: the checks about the provider's state. */
internal class KitStateChecks(private val subject: KitSubject, private val fixture: KeystoreConformanceFixture) {
    private val recorder = subject.recorder
    private val provider = subject.provider

    fun checkDescriptor() {
        val descriptor = subject.descriptor
        recorder.capture(Origin("the descriptor"), descriptor.toString())
        if (!descriptor.id.isWellFormed) recorder.fail(KeystoreRule.DESCRIPTOR, "the descriptor id is malformed")
        val again = runCatching { provider.describe() }.getOrNull()
        if (again != descriptor) recorder.fail(KeystoreRule.DESCRIPTOR, "describe did not answer the same descriptor twice")
    }

    /** Answers whether the provider ended unlocked, so the data checks can run. */
    suspend fun checkLocking(): Boolean {
        if (subject.descriptor.needsUnlock) return checkLockCycle()
        val material = recorder.secret(SentinelTag("unneeded-material"))
        recorder.expectError(KeystoreRule.LOCKING, Origin("unlock of a provider that needs none"), unsupported(KeystoreOperation.UNLOCK)) {
            provider.unlock(material)
        }
        recorder.expectError(KeystoreRule.LOCKING, Origin("lock of a provider that needs none"), unsupported(KeystoreOperation.LOCK)) {
            provider.lock()
        }
        return true
    }

    private suspend fun checkLockCycle(): Boolean {
        val material = recorder.attempt(Origin("the fixture's unlock material")) { fixture.unlockMaterial() }
        if (material == null) {
            recorder.fail(KeystoreRule.LOCKING, "the provider needs unlock but the fixture supplies no material")
            return false
        }
        recorder.watch(material)
        expectLocked(Origin("a fresh provider"))
        val wrong = recorder.secret(SentinelTag("wrong-material"))
        recorder.expectError(KeystoreRule.LOCKING, Origin("unlock with wrong material"), KeystoreError.Denied(KeystoreOperation.UNLOCK)) {
            provider.unlock(wrong)
        }
        if (!unlock(material, Origin("unlock with the fixture's material"))) return false
        unlock(material, Origin("a second unlock"))
        relock()
        return unlock(material, Origin("unlock after lock"))
    }

    private suspend fun relock() {
        recorder.expectSuccess(KeystoreRule.LOCKING, Origin("lock")) { provider.lock() }
        expectLocked(Origin("a relocked provider"))
        recorder.expectSuccess(KeystoreRule.LOCKING, Origin("a second lock")) { provider.lock() }
    }

    private suspend fun unlock(material: SecretValue, label: Origin): Boolean =
        recorder.expectSuccess(KeystoreRule.LOCKING, label) { provider.unlock(material) } != null

    private suspend fun expectLocked(label: Origin) {
        subject.expectStatus(KeystoreRule.LOCKING, HealthStatus.LOCKED, label)
        val operation = DATA_OPERATIONS.firstOrNull(subject::supports) ?: return
        val value = recorder.secret(SentinelTag("while-locked"))
        recorder.expectError(KeystoreRule.LOCKING, Origin("${operation.label()} of ${label.label}"), KeystoreError.Locked) {
            subject.perform(operation, subject.item(ItemName("round-trip")), value)
        }
    }

    suspend fun checkReady() {
        subject.expectStatus(KeystoreRule.HEALTH, HealthStatus.READY, Origin("a reachable, unlocked provider"))
    }

    /** Last check of a run: it takes the backend down for good. */
    suspend fun checkFailClosed() {
        val down = recorder.attempt(Origin("the fixture taking the backend down")) { fixture.makeUnavailable(provider) }
        if (down != true) return recorder.skip(KeystoreRule.FAIL_CLOSED)
        subject.expectStatus(KeystoreRule.FAIL_CLOSED, HealthStatus.UNAVAILABLE, Origin("a provider whose backend is down"))
        for (operation in DATA_OPERATIONS.filter(subject::supports)) {
            val value = recorder.secret(SentinelTag("fail-closed"))
            val label = Origin("${operation.label()} with the backend down")
            recorder.expectCode(KeystoreRule.FAIL_CLOSED, label, KeystoreErrorCode.UNAVAILABLE) {
                subject.perform(operation, subject.item(ItemName("round-trip")), value)
            }
        }
    }

    private fun unsupported(operation: KeystoreOperation): KeystoreError = KeystoreError.Unsupported(operation)
}
