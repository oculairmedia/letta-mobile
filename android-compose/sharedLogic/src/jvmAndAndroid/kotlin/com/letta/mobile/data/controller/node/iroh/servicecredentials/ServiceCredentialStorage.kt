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

/**
 * The host's store for service secrets. A secret is stored with the origin it was entered for and is
 * only ever returned for that origin, so settings that moved to another address can never hand the
 * old secret to it. A keystore-backed implementation can replace the file one.
 */
internal interface ServiceSecretStore {
    /** The secret of [serviceId] stored for [origin], or null. */
    fun read(serviceId: ServiceId, origin: String): String?

    /** Stores [secret] of [serviceId] for [origin], replacing any stored one. */
    fun write(serviceId: ServiceId, origin: String, secret: String)

    /** Deletes the secret of [serviceId]. */
    fun delete(serviceId: ServiceId)
}

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
    private val stored = mutableMapOf<ServiceId, Pair<String, String>>()

    override fun read(serviceId: ServiceId, origin: String): String? = stored[serviceId]?.takeIf { it.first == origin }?.second

    override fun write(serviceId: ServiceId, origin: String, secret: String) {
        stored[serviceId] = origin to secret
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
 */
internal class FileServiceSecretStore(private val path: Path) : ServiceSecretStore {
    override fun read(serviceId: ServiceId, origin: String): String? =
        readAll()[serviceId.value]?.takeIf { it.origin == origin }?.secret

    override fun write(serviceId: ServiceId, origin: String, secret: String) {
        writeAll(readAll() + (serviceId.value to StoredSecret(origin, secret)))
    }

    override fun delete(serviceId: ServiceId) {
        val all = readAll()
        if (serviceId.value in all) writeAll(all - serviceId.value)
    }

    override fun toString(): String = "FileServiceSecretStore"

    private fun readAll(): Map<String, StoredSecret> {
        val text = AtomicJsonFile.readOrNull(path) ?: return emptyMap()
        return runCatching { storeJson.decodeFromString(StoredSecrets.serializer(), text).secrets }.getOrDefault(emptyMap())
    }

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
