package com.letta.mobile.plugin.testkit.keystore

import com.letta.mobile.plugin.keystore.KeystoreCapability
import com.letta.mobile.plugin.keystore.KeystoreError
import com.letta.mobile.plugin.keystore.KeystoreSpi
import com.letta.mobile.plugin.keystore.SecretField
import com.letta.mobile.plugin.keystore.SecretListing
import com.letta.mobile.plugin.keystore.SecretRef
import com.letta.mobile.plugin.keystore.SecretScope

/** Delete and list: the checks about which items exist. */
internal class KitLifecycleChecks(private val subject: KitSubject) {
    private val recorder = subject.recorder
    private val provider = subject.provider
    private val canWrite = subject.supportsAll(KeystoreCapability.WRITE)

    suspend fun run() {
        checkDelete()
        checkList()
    }

    private suspend fun checkDelete() {
        if (!subject.supportsAll(KeystoreCapability.DELETE)) return recorder.skip(KeystoreRule.DELETE)
        val never = subject.item(ItemName("never-written"))
        expectDeleteMissing(never)
        if (!canWrite) return
        val doomed = subject.item(ItemName("doomed"))
        val slot = subject.slot(ItemName("doomed"), SecretField("token"))
        seed(KeystoreRule.DELETE, listOf(doomed, slot))
        recorder.expectSuccess(KeystoreRule.DELETE, Origin("delete of an item")) { provider.delete(doomed) }
        expectGone(doomed)
        expectGone(slot)
        expectDeleteMissing(doomed)
    }

    private suspend fun expectDeleteMissing(ref: SecretRef) {
        val label = Origin("delete of the missing $ref")
        recorder.expectError(KeystoreRule.DELETE, label, KeystoreError.NotFound(ref)) { provider.delete(ref) }
    }

    private suspend fun expectGone(ref: SecretRef) {
        if (!subject.supportsAll(KeystoreCapability.READ)) return
        recorder.expectError(KeystoreRule.DELETE, Origin("get of the deleted $ref"), KeystoreError.NotFound(ref)) { provider.get(ref) }
    }

    private suspend fun checkList() {
        if (!subject.supportsAll(KeystoreCapability.LIST)) return recorder.skip(KeystoreRule.LIST)
        listShape(SecretScope(subject.id), Origin("list of the whole provider"))
        if (canWrite) checkListedItems() else listShape(subject.scope(ItemName("listed")), Origin("list of a scope"))
    }

    /** Two items under the scope (one with two slots) and a sibling whose name only shares a prefix. */
    private suspend fun checkListedItems() {
        val first = subject.item(ItemName("listed/a"))
        val second = subject.item(ItemName("listed/b"))
        val secondSlot = subject.slot(ItemName("listed/b"), SecretField("token"))
        seed(KeystoreRule.LIST, listOf(second, secondSlot, first, subject.item(ItemName("listed-sibling"))))
        val listing = listShape(subject.scope(ItemName("listed")), Origin("list of a seeded scope")) ?: return
        if (listing.entries != listOf(first, second)) {
            recorder.fail(KeystoreRule.LIST, "list of a seeded scope answered ${listing.entries} instead of [$first, $second]")
        }
        if (listing.truncated) recorder.fail(KeystoreRule.LIST, "list of a seeded scope with two items says it is truncated")
    }

    private suspend fun listShape(scope: SecretScope, label: Origin): SecretListing? {
        val listing = recorder.expectSuccess(KeystoreRule.LIST, label) { provider.list(scope) } ?: return null
        val entries = listing.entries
        if (entries.any { it.field != null }) recorder.fail(KeystoreRule.LIST, "${label.label} lists a field, not only items")
        if (!entries.all(scope::contains)) recorder.fail(KeystoreRule.LIST, "${label.label} lists a name outside its scope")
        if (entries != entries.distinct().sortedBy { it.path.value }) {
            recorder.fail(KeystoreRule.LIST, "${label.label} is not distinct and sorted by path")
        }
        if (entries.size > KeystoreSpi.MAX_LIST_ENTRIES) recorder.fail(KeystoreRule.LIST, "${label.label} exceeds the entry limit")
        return listing
    }

    private suspend fun seed(rule: KeystoreRule, refs: List<SecretRef>) {
        refs.forEach { ref ->
            val value = recorder.secret(SentinelTag("seed"))
            recorder.expectSuccess(rule, Origin("put of the seed $ref")) { provider.put(ref, value) }
            value.wipe()
        }
    }
}
