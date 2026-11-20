package com.letta.mobile.data.controller.node.iroh.servicecredentials

import com.letta.mobile.data.controller.node.iroh.AtomicJsonFile
import com.letta.mobile.data.controller.node.iroh.IrohSecretKeyStores
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.servicecredentials.ServiceId
import com.letta.mobile.data.servicecredentials.ServiceTestStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** One service's stored settings, without its secret: the address override, the settings, the revision and the last test. */
@Serializable
internal data class StoredServiceCredential(
    @SerialName("revision") val revision: Long,
    @SerialName("base_url") val baseUrl: String? = null,
    @SerialName("config") val config: Map<String, String> = emptyMap(),
    @SerialName("last_test") val lastTest: ServiceTestStatus = ServiceTestStatus(),
)

/**
 * Everything the settings file holds: the last revision number ever issued (it keeps counting across a
 * removal, so an old `expected_revision` never matches newer settings) and each service's settings by id.
 */
@Serializable
internal data class ServiceCredentialState(
    @SerialName("revision") val revision: Long = 0,
    @SerialName("services") val services: Map<String, StoredServiceCredential> = emptyMap(),
)

/** Where the settings (without secrets) are kept. */
internal interface ServiceCredentialPersistence {
    /** The stored state; empty when nothing was ever stored. */
    fun read(): ServiceCredentialState

    /** Replaces the stored state. */
    fun write(state: ServiceCredentialState)
}

/** A service [secret] together with the [origin] it was entered for. Prints no secret. */
internal class BoundSecret(val origin: String, val secret: String) {
    override fun toString(): String = "BoundSecret"
}

/**
 * The host's store for service secrets. A secret is stored with the origin it was entered for and is
 * only ever returned for that origin, so settings that moved to another address can never hand the
 * old secret to it. A keystore-backed implementation can replace the file one.
 */
internal interface ServiceSecretStore {
    /** The secret of [serviceId] stored for [origin], or null. */
    fun read(serviceId: ServiceId, origin: String): String?

    /** Stores [secret] for [serviceId], replacing any stored one. */
    fun write(serviceId: ServiceId, secret: BoundSecret)

    /** Deletes the secret of [serviceId]. */
    fun delete(serviceId: ServiceId)
}

/** The secret file exists but cannot be decoded; nothing was written, and its bytes are untouched. */
internal class SecretFileUnreadable : IllegalStateException("The service secret file cannot be decoded; it is left untouched")

/** The settings in memory: tests. */
internal class InMemoryServiceCredentialPersistence(
    private var state: ServiceCredentialState = ServiceCredentialState(),
) : ServiceCredentialPersistence {
    override fun read(): ServiceCredentialState = state

    override fun write(state: ServiceCredentialState) {
        this.state = state
    }
}

/** Secrets in memory: tests only. */
internal class InMemoryServiceSecretStore : ServiceSecretStore {
    private val stored = mutableMapOf<ServiceId, BoundSecret>()

    override fun read(serviceId: ServiceId, origin: String): String? = stored[serviceId]?.takeIf { it.origin == origin }?.secret

    override fun write(serviceId: ServiceId, secret: BoundSecret) {
        stored[serviceId] = secret
    }

    override fun delete(serviceId: ServiceId) {
        stored.remove(serviceId)
    }

    override fun toString(): String = "InMemoryServiceSecretStore(services=${stored.size})"
}

/** The settings as a small JSON file, replaced atomically. Holds no secret. */
internal class FileServiceCredentialPersistence(private val path: Path) : ServiceCredentialPersistence {
    override fun read(): ServiceCredentialState = AtomicJsonFile.readOrNull(path)
        ?.let { storeJson.decodeFromString(ServiceCredentialState.serializer(), it) } ?: ServiceCredentialState()

    override fun write(state: ServiceCredentialState) {
        AtomicJsonFile.write(path, storeJson.encodeToString(ServiceCredentialState.serializer(), state))
    }
}

/**
 * Every service secret in one file only the host's user may read, written through
 * [IrohSecretKeyStores.writeOwnerOnly] like the speech server key. Removing the last secret deletes it.
 * A file that cannot be decoded is never overwritten: a write or delete fails and leaves its bytes
 * alone (an operator repairs or removes it), while a read sees no usable secret.
 */
internal class FileServiceSecretStore(private val path: Path) : ServiceSecretStore {
    override fun read(serviceId: ServiceId, origin: String): String? =
        readAllOrEmpty()[serviceId.value]?.takeIf { it.origin == origin }?.secret

    override fun write(serviceId: ServiceId, secret: BoundSecret) {
        writeAll(readAll() + (serviceId.value to StoredSecret(secret.origin, secret.secret)))
    }

    override fun delete(serviceId: ServiceId) {
        val all = readAll()
        if (serviceId.value in all) writeAll(all - serviceId.value)
    }

    override fun toString(): String = "FileServiceSecretStore"

    /** Every stored secret; empty only when there is no file. A file that cannot be decoded is never treated as empty. */
    private fun readAll(): Map<String, StoredSecret> {
        val text = AtomicJsonFile.readOrNull(path) ?: return emptyMap()
        return decode(text) ?: throw SecretFileUnreadable()
    }

    /** What a read (a status, a test) sees: a file that cannot be decoded holds no usable secret; a write refuses it. */
    private fun readAllOrEmpty(): Map<String, StoredSecret> =
        AtomicJsonFile.readOrNull(path)?.let(::decode).orEmpty()

    private fun decode(text: String): Map<String, StoredSecret>? =
        runCatching { storeJson.decodeFromString(StoredSecrets.serializer(), text).secrets }.getOrNull()

    private fun writeAll(secrets: Map<String, StoredSecret>) {
        if (secrets.isEmpty()) {
            Files.deleteIfExists(path)
        } else {
            IrohSecretKeyStores.writeOwnerOnly(path, storeJson.encodeToString(StoredSecrets.serializer(), StoredSecrets(secrets)))
        }
    }

    @Serializable
    private class StoredSecrets(@SerialName("secrets") val secrets: Map<String, StoredSecret>)

    @Serializable
    private class StoredSecret(
        @SerialName("origin") val origin: String,
        @SerialName("secret") val secret: String,
    )
}

private val storeJson = LettaJson.persisted
