package com.letta.mobile.plugin.testkit.keystore

import com.letta.mobile.plugin.keystore.KeystoreCapability
import com.letta.mobile.plugin.keystore.KeystoreError
import com.letta.mobile.plugin.keystore.KeystoreOperation
import com.letta.mobile.plugin.keystore.KeystoreSpi
import com.letta.mobile.plugin.keystore.RefProblem
import com.letta.mobile.plugin.keystore.SecretField
import com.letta.mobile.plugin.keystore.SecretPath
import com.letta.mobile.plugin.keystore.SecretRef
import com.letta.mobile.plugin.keystore.SecretValue
import com.letta.mobile.plugin.keystore.ValueProblem

/** One invalid ref the kit sends, what it is, and the problem the provider must name. */
internal class RefCase(val label: Origin, val ref: SecretRef, val problem: RefProblem, val fieldOnly: Boolean = false)

/** Capability refusals, invalid refs and invalid values: the checks that need no stored data. */
internal class KitInputChecks(private val subject: KitSubject) {
    private val recorder = subject.recorder
    private val target = ItemName("input")

    suspend fun run() {
        checkCapabilities()
        checkInvalidRefs()
        checkInvalidValues()
    }

    private suspend fun checkCapabilities() {
        val undeclared = DATA_OPERATIONS.filterNot(subject::supports)
        if (undeclared.isEmpty()) recorder.skip(KeystoreRule.CAPABILITY)
        val malformed = SecretRef(subject.id, SecretPath("${subject.path(target).value}//x"))
        for (operation in undeclared) {
            expectUnsupported(operation, subject.item(target), Origin("${operation.label()} without its capability"))
            expectUnsupported(operation, malformed, Origin("${operation.label()} of a malformed ref without its capability"))
        }
    }

    private suspend fun expectUnsupported(operation: KeystoreOperation, ref: SecretRef, label: Origin) {
        val value = recorder.secret(SentinelTag("unsupported"))
        val expected = KeystoreError.Unsupported(operation)
        recorder.expectError(KeystoreRule.CAPABILITY, label, expected) { subject.perform(operation, ref, value) }
    }

    private suspend fun checkInvalidRefs() {
        for (operation in DATA_OPERATIONS.filter(subject::supports)) {
            val cases = refCases().filterNot { it.fieldOnly && operation == KeystoreOperation.LIST }
            cases.forEach { case -> expectInvalidRef(operation, case) }
        }
        if (subject.supports(KeystoreOperation.DELETE)) {
            val withField = subject.slot(target, SecretField("token"))
            recorder.expectError(
                KeystoreRule.INVALID_REF,
                Origin("delete of a ref with a field"),
                KeystoreError.InvalidRef(RefProblem.FIELD_NOT_ALLOWED),
            ) { subject.provider.delete(withField) }
        }
    }

    /** `put` sends an empty value too: an invalid ref is reported before an invalid value. */
    private suspend fun expectInvalidRef(operation: KeystoreOperation, case: RefCase) {
        val label = Origin("${operation.label()} of ${case.label.label}")
        val expected = KeystoreError.InvalidRef(case.problem)
        val empty = SecretValue.ofBytes(ByteArray(0))
        recorder.expectError(KeystoreRule.INVALID_REF, label, expected) { subject.perform(operation, case.ref, empty) }
    }

    private fun refCases(): List<RefCase> {
        val base = subject.path(target).value
        return listOf(
            RefCase(Origin("a path with a .. segment"), SecretRef(subject.id, SecretPath("$base/../escape")), RefProblem.MALFORMED),
            RefCase(Origin("a path with an empty segment"), SecretRef(subject.id, SecretPath("$base//x")), RefProblem.MALFORMED),
            RefCase(Origin("a path with a trailing slash"), SecretRef(subject.id, SecretPath("$base/")), RefProblem.MALFORMED),
            RefCase(Origin("a field named .."), subject.slot(target, SecretField("..")), RefProblem.MALFORMED, fieldOnly = true),
            RefCase(Origin("a ref to another provider"), SecretRef(subject.foreignId, SecretPath(base)), RefProblem.PROVIDER_MISMATCH),
        )
    }

    private suspend fun checkInvalidValues() {
        if (!subject.supportsAll(KeystoreCapability.WRITE)) return recorder.skip(KeystoreRule.INVALID_VALUE)
        expectInvalidValue(ItemName("empty"), ByteArray(0), ValueProblem.EMPTY)
        expectInvalidValue(ItemName("too-large"), ByteArray(KeystoreSpi.MAX_VALUE_BYTES + 1) { 'x'.code.toByte() }, ValueProblem.TOO_LARGE)
    }

    private suspend fun expectInvalidValue(name: ItemName, bytes: ByteArray, problem: ValueProblem) {
        val ref = subject.item(name)
        val label = Origin("put of an invalid value (${problem.name.lowercase()})")
        val value = SecretValue.ofBytes(bytes)
        recorder.expectError(KeystoreRule.INVALID_VALUE, label, KeystoreError.InvalidValue(problem)) { subject.provider.put(ref, value) }
        if (!subject.supportsAll(KeystoreCapability.READ)) return
        val readBack = Origin("get after a refused put (${problem.name.lowercase()})")
        recorder.expectError(KeystoreRule.INVALID_VALUE, readBack, KeystoreError.NotFound(ref)) { subject.provider.get(ref) }
    }
}
