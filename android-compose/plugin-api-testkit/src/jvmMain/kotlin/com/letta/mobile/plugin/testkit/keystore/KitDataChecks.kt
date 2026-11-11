package com.letta.mobile.plugin.testkit.keystore

import com.letta.mobile.plugin.keystore.KeystoreCapability
import com.letta.mobile.plugin.keystore.KeystoreError
import com.letta.mobile.plugin.keystore.PutOptions
import com.letta.mobile.plugin.keystore.SecretDescription
import com.letta.mobile.plugin.keystore.SecretField
import com.letta.mobile.plugin.keystore.SecretMetadata
import com.letta.mobile.plugin.keystore.SecretRef
import com.letta.mobile.plugin.keystore.WriteMode

/** Not found, round trip and write modes: the checks that read back what they wrote. */
internal class KitDataChecks(private val subject: KitSubject) {
    private val recorder = subject.recorder
    private val provider = subject.provider
    private val token = SecretField("token")

    suspend fun run() {
        checkNotFound()
        checkRoundTrip()
        checkWriteModes()
        KitLifecycleChecks(subject).run()
    }

    private suspend fun checkNotFound() {
        if (!subject.supportsAll(KeystoreCapability.READ)) return recorder.skip(KeystoreRule.NOT_FOUND)
        expectMissing(KeystoreRule.NOT_FOUND, subject.item(ItemName("missing")))
        expectMissing(KeystoreRule.NOT_FOUND, subject.slot(ItemName("missing"), token))
    }

    private suspend fun checkRoundTrip() {
        if (!subject.supportsAll(KeystoreCapability.READ, KeystoreCapability.WRITE)) return recorder.skip(KeystoreRule.ROUND_TRIP)
        val item = subject.item(ROUND_TRIP)
        val slot = subject.slot(ROUND_TRIP, token)
        val first = recorder.secret(DEFAULT_SLOT)
        recorder.expectSuccess(KeystoreRule.ROUND_TRIP, Origin("put of a default slot")) { provider.put(item, first) }
        first.wipe()
        write(KeystoreRule.ROUND_TRIP, slot, FIELD_SLOT)
        expectValue(KeystoreRule.ROUND_TRIP, item, DEFAULT_SLOT)
        expectValue(KeystoreRule.ROUND_TRIP, slot, FIELD_SLOT)
        expectMissing(KeystoreRule.ROUND_TRIP, subject.slot(ROUND_TRIP, SecretField("absent")))
        checkFreshCopies(item)
    }

    /** Each get answers its own instance, and wiping one leaves the stored value intact. */
    private suspend fun checkFreshCopies(item: SecretRef) {
        val first = recorder.expectSuccess(KeystoreRule.ROUND_TRIP, Origin("a first get")) { provider.get(item) } ?: return
        val second = recorder.expectSuccess(KeystoreRule.ROUND_TRIP, Origin("a second get")) { provider.get(item) }
        if (first === second) recorder.fail(KeystoreRule.ROUND_TRIP, "two gets of $item answered the same instance")
        first.wipe()
        second?.wipe()
        expectValue(KeystoreRule.ROUND_TRIP, item, DEFAULT_SLOT)
    }

    private suspend fun checkWriteModes() {
        if (!subject.supportsAll(KeystoreCapability.WRITE)) return recorder.skip(KeystoreRule.WRITE_MODES)
        val ref = subject.item(ItemName("modes"))
        val metadata = SecretMetadata(SecretDescription("written by the keystore conformance kit"))
        write(KeystoreRule.WRITE_MODES, ref, MODE_FIRST, PutOptions(WriteMode.CREATE, metadata))
        val again = recorder.secret(MODE_SECOND)
        recorder.expectError(KeystoreRule.WRITE_MODES, Origin("a second create"), KeystoreError.Conflict(ref)) {
            provider.put(ref, again, PutOptions(WriteMode.CREATE))
        }
        expectValueIfReadable(ref, MODE_FIRST)
        val missing = subject.item(ItemName("modes-missing"))
        recorder.expectError(KeystoreRule.WRITE_MODES, Origin("replace of a missing slot"), KeystoreError.NotFound(missing)) {
            provider.put(missing, again, PutOptions(WriteMode.REPLACE))
        }
        write(KeystoreRule.WRITE_MODES, ref, MODE_SECOND, PutOptions(WriteMode.REPLACE))
        expectValueIfReadable(ref, MODE_SECOND)
        write(KeystoreRule.WRITE_MODES, ref, MODE_THIRD)
        write(KeystoreRule.WRITE_MODES, subject.item(ItemName("modes-upsert")), MODE_THIRD)
        expectValueIfReadable(ref, MODE_THIRD)
    }

    private suspend fun expectValueIfReadable(ref: SecretRef, tag: SentinelTag) {
        if (subject.supportsAll(KeystoreCapability.READ)) expectValue(KeystoreRule.WRITE_MODES, ref, tag)
    }

    private suspend fun write(rule: KeystoreRule, ref: SecretRef, tag: SentinelTag, options: PutOptions = PutOptions()) {
        val value = recorder.secret(tag)
        recorder.expectSuccess(rule, Origin("put (${options.mode.name.lowercase()}) of $ref")) { provider.put(ref, value, options) }
        value.wipe()
    }

    private suspend fun expectMissing(rule: KeystoreRule, ref: SecretRef) {
        recorder.expectError(rule, Origin("get of the missing $ref"), KeystoreError.NotFound(ref)) { provider.get(ref) }
    }

    private suspend fun expectValue(rule: KeystoreRule, ref: SecretRef, tag: SentinelTag) {
        val value = recorder.expectSuccess(rule, Origin("get of $ref")) { provider.get(ref) } ?: return
        val expected = recorder.secret(tag)
        val matches = !value.isWiped && value.contentEqualsConstantTime(expected)
        if (!matches) recorder.fail(rule, "get of $ref answered a value other than the one written")
        value.wipe()
        expected.wipe()
    }

    private companion object {
        val ROUND_TRIP = ItemName("round-trip")
        val DEFAULT_SLOT = SentinelTag("round-trip-default")
        val FIELD_SLOT = SentinelTag("round-trip-field")
        val MODE_FIRST = SentinelTag("mode-first")
        val MODE_SECOND = SentinelTag("mode-second")
        val MODE_THIRD = SentinelTag("mode-third")
    }
}
