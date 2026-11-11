package com.letta.mobile.plugin.testkit.keystore

import com.letta.mobile.plugin.keystore.KeystoreCapability
import com.letta.mobile.plugin.keystore.KeystoreError
import com.letta.mobile.plugin.keystore.KeystoreOperation
import com.letta.mobile.plugin.keystore.KeystoreResult
import com.letta.mobile.plugin.keystore.ProviderDiagnostic
import com.letta.mobile.plugin.keystore.PutOptions
import com.letta.mobile.plugin.keystore.RefProblem
import com.letta.mobile.plugin.keystore.SecretRef
import com.letta.mobile.plugin.keystore.SecretValue
import com.letta.mobile.plugin.keystore.ValueProblem

/** Deliberately broken in-memory providers, each breaking one contract rule. */

/** Answers success with an empty value while its backend is down (I3). */
internal class FailsOpen : InMemoryKeystoreProvider() {
    @Suppress("UNCHECKED_CAST")
    override fun <T> whenUnavailable(): KeystoreResult<T> = KeystoreResult.Success(SecretValue.ofBytes(ByteArray(0))) as KeystoreResult<T>
}

/** Declares read and list only, yet writes and deletes (S2). */
internal class IgnoresCapabilities : InMemoryKeystoreProvider(setOf(KeystoreCapability.READ, KeystoreCapability.LIST)) {
    override fun unsupported(operation: KeystoreOperation): KeystoreError? = null
}

/** Serves malformed refs instead of refusing them (R3). */
internal class AcceptsMalformedRefs : InMemoryKeystoreProvider() {
    override fun refProblem(ref: SecretRef): RefProblem? =
        super.refProblem(ref).takeUnless { it == RefProblem.MALFORMED }
}

/** Stores empty and oversized values (S4). */
internal class AcceptsInvalidValues : InMemoryKeystoreProvider() {
    override fun valueProblem(value: SecretValue): ValueProblem? = null
}

/** Throws for a missing slot instead of answering `not_found`. */
internal class ThrowsOnMissing : InMemoryKeystoreProvider() {
    override fun missing(ref: SecretRef): KeystoreResult<Nothing> = throw NoSuchElementException("missing")
}

/** Hands out its stored instance, so wiping a read wipes the store (V4). */
internal class SharesInstances : InMemoryKeystoreProvider() {
    override fun read(stored: SecretValue): SecretValue = stored
}

/** Lists slots with their fields instead of items (S8). */
internal class ListsFields : InMemoryKeystoreProvider() {
    override fun listedName(stored: SecretRef): SecretRef = stored
}

/** Claims to need unlock but starts open (S9). */
internal class StartsUnlocked(material: SecretValue) : InMemoryKeystoreProvider(needsUnlock = true, material = material) {
    init {
        unlocked = true
    }
}

/** Puts the last written value into its unavailable diagnostic (E1, S11). */
internal class LeaksInDiagnostic : InMemoryKeystoreProvider() {
    private var last: String = ""

    override suspend fun put(ref: SecretRef, value: SecretValue, options: PutOptions): KeystoreResult<Unit> {
        last = value.revealSecretBytes { it.decodeToString() }
        return super.put(ref, value, options)
    }

    override fun <T> whenUnavailable(): KeystoreResult<T> = KeystoreResult.Failure(KeystoreError.Unavailable(ProviderDiagnostic(last)))
}

/** Prints the last written value in its toString (I1). */
internal class LeaksInToString : InMemoryKeystoreProvider() {
    private var last: String = ""

    override suspend fun put(ref: SecretRef, value: SecretValue, options: PutOptions): KeystoreResult<Unit> {
        last = value.revealSecretBytes { it.decodeToString() }
        return super.put(ref, value, options)
    }

    override fun toString(): String = "LeaksInToString(last=$last)"
}

/** Writes every stored value to its own log (I1), which the fixture hands the kit. */
internal class LogsValues(private val log: MutableList<String>) : InMemoryKeystoreProvider() {
    override suspend fun put(ref: SecretRef, value: SecretValue, options: PutOptions): KeystoreResult<Unit> {
        log += "stored $ref = ${value.revealSecretBytes { it.decodeToString() }}"
        return super.put(ref, value, options)
    }
}
