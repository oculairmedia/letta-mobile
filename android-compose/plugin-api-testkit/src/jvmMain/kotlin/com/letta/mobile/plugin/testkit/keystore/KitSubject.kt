package com.letta.mobile.plugin.testkit.keystore

import com.letta.mobile.plugin.keystore.HealthStatus
import com.letta.mobile.plugin.keystore.KeystoreCapability
import com.letta.mobile.plugin.keystore.KeystoreDescriptor
import com.letta.mobile.plugin.keystore.KeystoreOperation
import com.letta.mobile.plugin.keystore.KeystoreProvider
import com.letta.mobile.plugin.keystore.KeystoreProviderId
import com.letta.mobile.plugin.keystore.KeystoreResult
import com.letta.mobile.plugin.keystore.SecretField
import com.letta.mobile.plugin.keystore.SecretPath
import com.letta.mobile.plugin.keystore.SecretRef
import com.letta.mobile.plugin.keystore.SecretScope
import com.letta.mobile.plugin.keystore.SecretValue

/** The data operations, in the order the kit exercises them. */
internal val DATA_OPERATIONS: List<KeystoreOperation> =
    listOf(KeystoreOperation.GET, KeystoreOperation.PUT, KeystoreOperation.LIST, KeystoreOperation.DELETE)

/** An item name under the fixture's workspace. */
@JvmInline
internal value class ItemName(val value: String)

/** The provider under test, its descriptor, and refs under the fixture's workspace. */
internal class KitSubject(
    val provider: KeystoreProvider,
    val descriptor: KeystoreDescriptor,
    val recorder: KitRecorder,
    private val workspace: SecretPath,
) {
    val id: KeystoreProviderId get() = descriptor.id

    /** An id that is well formed and not [id], for provider-mismatch checks. */
    val foreignId: KeystoreProviderId
        get() = KeystoreProviderId(if (id.value == FOREIGN) "$FOREIGN-b" else FOREIGN)

    fun supports(operation: KeystoreOperation): Boolean = operation.capability?.let(descriptor.capabilities::contains) ?: false

    fun supportsAll(vararg capabilities: KeystoreCapability): Boolean = descriptor.capabilities.containsAll(capabilities.toList())

    fun path(name: ItemName): SecretPath = SecretPath("${workspace.value}/${name.value}")

    fun item(name: ItemName): SecretRef = SecretRef(id, path(name))

    fun slot(name: ItemName, field: SecretField): SecretRef = SecretRef(id, path(name), field)

    fun scope(name: ItemName): SecretScope = SecretScope(id, path(name))

    /** Runs data [operation] on [ref] (`list` lists the scope at its path); [value] is what `put` writes. */
    suspend fun perform(operation: KeystoreOperation, ref: SecretRef, value: SecretValue): KeystoreResult<*> = when (operation) {
        KeystoreOperation.GET -> provider.get(ref)
        KeystoreOperation.PUT -> provider.put(ref, value)
        KeystoreOperation.LIST -> provider.list(SecretScope(ref.provider, ref.path))
        KeystoreOperation.DELETE -> provider.delete(ref)
        else -> error("${operation.label()} is not a data operation")
    }

    /** Fails [rule] unless `health` answers [status]; captures the report either way. */
    suspend fun expectStatus(rule: KeystoreRule, status: HealthStatus, label: Origin) {
        val health = recorder.attempt(Origin("health of ${label.label}")) { provider.health() } ?: return
        recorder.capture(Origin("the health of ${label.label}"), health.toString())
        recorder.checkDiagnostic(Origin("the health diagnostic of ${label.label}"), health.detail)
        if (health.status != status) recorder.fail(rule, "health of ${label.label} answered ${health.status} instead of $status")
    }

    private companion object {
        const val FOREIGN = "conformance.foreign"
    }
}

/** The lowercase operation name used in findings. */
internal fun KeystoreOperation.label(): String = name.lowercase()
