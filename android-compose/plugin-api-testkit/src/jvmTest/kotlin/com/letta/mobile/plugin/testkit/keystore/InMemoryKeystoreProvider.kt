package com.letta.mobile.plugin.testkit.keystore

import com.letta.mobile.plugin.keystore.HealthStatus
import com.letta.mobile.plugin.keystore.KeystoreCapability
import com.letta.mobile.plugin.keystore.KeystoreDescriptor
import com.letta.mobile.plugin.keystore.KeystoreError
import com.letta.mobile.plugin.keystore.KeystoreHealth
import com.letta.mobile.plugin.keystore.KeystoreOperation
import com.letta.mobile.plugin.keystore.KeystoreProvider
import com.letta.mobile.plugin.keystore.KeystoreProviderId
import com.letta.mobile.plugin.keystore.KeystoreResult
import com.letta.mobile.plugin.keystore.KeystoreSpi
import com.letta.mobile.plugin.keystore.ProviderDiagnostic
import com.letta.mobile.plugin.keystore.PutOptions
import com.letta.mobile.plugin.keystore.RefProblem
import com.letta.mobile.plugin.keystore.SecretListing
import com.letta.mobile.plugin.keystore.SecretRef
import com.letta.mobile.plugin.keystore.SecretScope
import com.letta.mobile.plugin.keystore.SecretValue
import com.letta.mobile.plugin.keystore.ValueProblem
import com.letta.mobile.plugin.keystore.WriteMode

/**
 * A minimal keystore provider that keeps everything in memory: the reference the conformance kit
 * is proven against. Test sources only; it is no storage anyone should use. Its `open` members are
 * the seams the deliberately broken variants in [BrokenKeystoreProviders] override.
 */
internal open class InMemoryKeystoreProvider(
    capabilities: Set<KeystoreCapability> = KeystoreCapability.entries.toSet(),
    needsUnlock: Boolean = false,
    private val material: SecretValue? = null,
) : KeystoreProvider {
    private val descriptor = KeystoreDescriptor(KeystoreProviderId("example.memory"), capabilities, needsUnlock)
    protected val slots: MutableMap<SecretRef, SecretValue> = mutableMapOf()
    protected var unlocked: Boolean = !needsUnlock

    /** Switched off by the fixture to take the "backend" down. */
    var available: Boolean = true

    override fun describe(): KeystoreDescriptor = descriptor

    override suspend fun health(): KeystoreHealth = KeystoreHealth(
        when {
            !available -> HealthStatus.UNAVAILABLE
            !unlocked -> HealthStatus.LOCKED
            else -> HealthStatus.READY
        },
    )

    override suspend fun unlock(material: SecretValue): KeystoreResult<Unit> {
        if (!descriptor.needsUnlock) return KeystoreResult.Failure(KeystoreError.Unsupported(KeystoreOperation.UNLOCK))
        if (!available) return KeystoreResult.Failure(unavailable())
        if (this.material?.contentEqualsConstantTime(material) != true) {
            return KeystoreResult.Failure(KeystoreError.Denied(KeystoreOperation.UNLOCK))
        }
        unlocked = true
        return KeystoreResult.Done
    }

    override suspend fun lock(): KeystoreResult<Unit> {
        if (!descriptor.needsUnlock) return KeystoreResult.Failure(KeystoreError.Unsupported(KeystoreOperation.LOCK))
        unlocked = false
        return KeystoreResult.Done
    }

    override suspend fun get(ref: SecretRef): KeystoreResult<SecretValue> = guarded(KeystoreOperation.GET, refProblem(ref)) {
        slots[ref]?.let { KeystoreResult.Success(read(it)) } ?: missing(ref)
    }

    override suspend fun put(ref: SecretRef, value: SecretValue, options: PutOptions): KeystoreResult<Unit> =
        guarded(KeystoreOperation.PUT, refProblem(ref), valueProblem(value)) { write(ref, value, options.mode) }

    override suspend fun list(scope: SecretScope): KeystoreResult<SecretListing> =
        guarded(KeystoreOperation.LIST, scope.problemFor(descriptor.id)) {
            val items = slots.keys.map(::listedName).filter(scope::contains).distinct().sortedBy { it.path.value }
            KeystoreResult.Success(SecretListing(items.take(KeystoreSpi.MAX_LIST_ENTRIES), items.size > KeystoreSpi.MAX_LIST_ENTRIES))
        }

    override suspend fun delete(ref: SecretRef): KeystoreResult<Unit> {
        val problem = refProblem(ref) ?: RefProblem.FIELD_NOT_ALLOWED.takeIf { ref.field != null }
        return guarded(KeystoreOperation.DELETE, problem) {
            val removed = slots.keys.filter { it.item == ref }
            removed.forEach { slots.remove(it)?.wipe() }
            if (removed.isEmpty()) missing(ref) else KeystoreResult.Done
        }
    }

    protected open fun refProblem(ref: SecretRef): RefProblem? = ref.problemFor(descriptor.id)

    protected open fun valueProblem(value: SecretValue): ValueProblem? = value.valueProblem

    protected open fun unsupported(operation: KeystoreOperation): KeystoreError? =
        KeystoreError.Unsupported(operation).takeUnless { operation.capability?.let(descriptor.capabilities::contains) ?: false }

    /** What `get` hands out for a stored value: a fresh copy. */
    protected open fun read(stored: SecretValue): SecretValue = stored.revealSecretBytes(SecretValue::ofBytes)

    protected open fun listedName(stored: SecretRef): SecretRef = stored.item

    protected open fun missing(ref: SecretRef): KeystoreResult<Nothing> = KeystoreResult.Failure(KeystoreError.NotFound(ref))

    /** What a data operation answers while the backend is down. */
    protected open fun <T> whenUnavailable(): KeystoreResult<T> = KeystoreResult.Failure(unavailable())

    protected fun unavailable(): KeystoreError = KeystoreError.Unavailable(ProviderDiagnostic("in-memory backend switched off"))

    private fun write(ref: SecretRef, value: SecretValue, mode: WriteMode): KeystoreResult<Unit> {
        val exists = ref in slots
        return when {
            mode == WriteMode.CREATE && exists -> KeystoreResult.Failure(KeystoreError.Conflict(ref))
            mode == WriteMode.REPLACE && !exists -> missing(ref)
            else -> {
                slots.put(ref, value.revealSecretBytes(SecretValue::ofBytes))?.wipe()
                KeystoreResult.Done
            }
        }
    }

    private inline fun <T> guarded(
        operation: KeystoreOperation,
        refProblem: RefProblem?,
        valueProblem: ValueProblem? = null,
        body: () -> KeystoreResult<T>,
    ): KeystoreResult<T> {
        val refused = unsupported(operation) ?: refProblem?.let(KeystoreError::InvalidRef) ?: valueProblem?.let(KeystoreError::InvalidValue)
        return when {
            refused != null -> KeystoreResult.Failure(refused)
            !available -> whenUnavailable()
            !unlocked -> KeystoreResult.Failure(KeystoreError.Locked)
            else -> body()
        }
    }
}

/** Hands the kit fresh in-memory providers and switches their backend off on request. */
internal class InMemoryFixture(
    private val output: List<String> = emptyList(),
    private val material: SecretValue? = null,
    private val create: (SecretValue?) -> InMemoryKeystoreProvider = { InMemoryKeystoreProvider(material = it) },
) : KeystoreConformanceFixture {
    override suspend fun newProvider(): KeystoreProvider = create(material)

    override suspend fun unlockMaterial(): SecretValue? = material

    override suspend fun makeUnavailable(provider: KeystoreProvider): Boolean {
        (provider as InMemoryKeystoreProvider).available = false
        return true
    }

    override fun capturedOutput(): List<String> = output
}
