package com.letta.mobile.data.controller.node.iroh.servicecredentials

import com.letta.mobile.data.hoststate.HostStateFileLocks
import com.letta.mobile.data.servicecredentials.ServiceCredentialErrorCode
import com.letta.mobile.data.servicecredentials.ServiceCredentialSetCommand
import com.letta.mobile.data.servicecredentials.ServiceCredentialStatus
import com.letta.mobile.data.servicecredentials.ServiceCredentialWire
import com.letta.mobile.data.servicecredentials.ServiceId
import com.letta.mobile.data.servicecredentials.ServiceSecretChange
import com.letta.mobile.data.servicecredentials.ServiceTestStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One service's stored credential at one revision, for one test. Prints no secret. */
internal class ServiceSnapshot(val revision: Long, val target: ServiceTarget) {
    override fun toString(): String = "ServiceSnapshot(revision=$revision)"
}

/**
 * The host's service credentials: settings without secrets ([persistence]) and secrets in the host's
 * secret store ([secrets]), for the services [adapters] serve. Every write takes a new revision and
 * resets the last test; a write to a new origin drops the secret unless it brings a new one. All access
 * is serialised under [lock]: stores over one settings file must share it (see [HostStateFileLocks]), or two
 * of them could accept writes against the same revision. Contacts no service.
 */
internal class ServiceCredentialStore(
    private val adapters: List<ServiceAdapter>,
    private val persistence: ServiceCredentialPersistence,
    private val secrets: ServiceSecretStore,
    private val lock: Mutex = Mutex(),
) {
    private val recovery = SecretRecovery(secrets)

    /** The adapter of [serviceId]; `service.unknown_service` when there is none. */
    fun adapter(serviceId: ServiceId): ServiceAdapter =
        adapters.firstOrNull { it.descriptor.id == serviceId } ?: serviceRefuse(ServiceCredentialErrorCode.UNKNOWN_SERVICE)

    /** Every known service's status, in adapter order. */
    suspend fun list(): List<ServiceCredentialStatus> = lock.withLock {
        val state = persistence.read()
        adapters.map { adapter ->
            recovery.recover(adapter.descriptor.id)
            statusOf(adapter.descriptor, state)
        }
    }

    /** Applies [command] and returns the service's new status. Throws [ServiceRefusal]. */
    suspend fun write(command: ServiceCredentialSetCommand): ServiceCredentialStatus = lock.withLock {
        val descriptor = adapter(command.serviceId).descriptor
        recovery.requireRecovered(descriptor.id)
        val state = persistence.read()
        val stored = state.services[descriptor.id.value]
        requireRevision(stored, command.expectedRevision)
        val address = requireAddress(descriptor, command.baseUrl)
        requireConfig(descriptor, command.config)
        val origin = ServiceAddress.originOf(address) ?: serviceRefuse(ServiceCredentialErrorCode.INVALID_REQUEST)
        val keepable = stored != null && previousOrigin(descriptor, stored) == origin
        if (secretAfter(descriptor.id, command.secret, origin, keepable) && !ServiceAddress.protectsSecret(address)) {
            serviceRefuse(ServiceCredentialErrorCode.INSECURE_TRANSPORT)
        }
        val next = StoredServiceCredential(state.revision + 1, command.baseUrl, command.config)
        val written = state.copy(revision = next.revision, services = state.services + (descriptor.id.value to next))
        recovery.guarded(descriptor.id, stored?.let { previousOrigin(descriptor, it) }) {
            applySecret(descriptor.id, command.secret, origin, keepable)
            persistence.write(written)
        }
        statusOf(descriptor, written)
    }

    /** Deletes [serviceId]'s settings, secret and last test; its empty status. Throws [ServiceRefusal]. */
    suspend fun remove(serviceId: ServiceId, expectedRevision: String?): ServiceCredentialStatus = lock.withLock {
        val descriptor = adapter(serviceId).descriptor
        recovery.requireRecovered(serviceId)
        val state = persistence.read()
        val stored = state.services[serviceId.value]
        requireRevision(stored, expectedRevision)
        val next = if (stored == null) state else state.copy(revision = state.revision + 1, services = state.services - serviceId.value)
        recovery.guarded(descriptor.id, stored?.let { previousOrigin(descriptor, it) }) {
            secrets.delete(serviceId)
            persistence.write(next)
        }
        statusOf(descriptor, next)
    }

    /** [serviceId]'s stored credential for a test; `service.not_configured` without settings or secret. */
    suspend fun snapshot(serviceId: ServiceId): ServiceSnapshot = lock.withLock {
        val descriptor = adapter(serviceId).descriptor
        recovery.requireRecovered(serviceId)
        val stored = persistence.read().services[serviceId.value] ?: serviceRefuse(ServiceCredentialErrorCode.NOT_CONFIGURED)
        val address = stored.baseUrl ?: descriptor.defaultBaseUrl ?: serviceRefuse(ServiceCredentialErrorCode.NOT_CONFIGURED)
        val origin = ServiceAddress.originOf(address) ?: serviceRefuse(ServiceCredentialErrorCode.NOT_CONFIGURED)
        val secret = secrets.read(serviceId, origin) ?: serviceRefuse(ServiceCredentialErrorCode.NOT_CONFIGURED)
        val defaults = descriptor.configFields.mapNotNull { field -> field.defaultValue?.let { field.key to it } }.toMap()
        ServiceSnapshot(stored.revision, ServiceTarget(address, secret, defaults + stored.config))
    }

    /** Records [test] against [serviceId] at [revision] (ignored when it changed since); the current status. */
    suspend fun recordTest(serviceId: ServiceId, revision: Long, test: ServiceTestStatus): ServiceCredentialStatus = lock.withLock {
        val descriptor = adapter(serviceId).descriptor
        recovery.recover(serviceId)
        val state = persistence.read()
        val stored = state.services[serviceId.value]
        val next = if (stored?.revision == revision) {
            state.copy(services = state.services + (serviceId.value to stored.copy(lastTest = test))).also(persistence::write)
        } else {
            state
        }
        statusOf(descriptor, next)
    }

    private fun requireRevision(stored: StoredServiceCredential?, expected: String?) {
        if (expected != null && expected != stored?.revisionName()) serviceRefuse(ServiceCredentialErrorCode.REVISION_CONFLICT)
    }

    /** The effective address: the override (only where the descriptor allows one) or the default. */
    private fun requireAddress(descriptor: ServiceDescriptor, override: String?): String {
        if (override != null && (!descriptor.baseUrlEditable || ServiceAddress.originOf(override) == null)) {
            serviceRefuse(ServiceCredentialErrorCode.INVALID_REQUEST)
        }
        return override ?: descriptor.defaultBaseUrl ?: serviceRefuse(ServiceCredentialErrorCode.INVALID_REQUEST)
    }

    private fun requireConfig(descriptor: ServiceDescriptor, config: Map<String, String>) {
        val valid = config.all { (key, value) ->
            descriptor.field(key) != null && value.isNotBlank() && value.length <= ServiceCredentialWire.MAX_CONFIG_VALUE_CHARS
        }
        if (!valid) serviceRefuse(ServiceCredentialErrorCode.INVALID_REQUEST)
    }

    private fun secretAfter(serviceId: ServiceId, change: ServiceSecretChange, origin: String, keepable: Boolean): Boolean = when (change) {
        is ServiceSecretChange.Replace -> true
        ServiceSecretChange.Remove -> false
        ServiceSecretChange.Keep -> keepable && secrets.read(serviceId, origin) != null
    }

    /** Stores, deletes or keeps the secret, bound to [origin]; it is written before the settings file. */
    private fun applySecret(serviceId: ServiceId, change: ServiceSecretChange, origin: String, keepable: Boolean) {
        when (change) {
            is ServiceSecretChange.Replace -> secrets.write(serviceId, BoundSecret(origin, change.secret.revealForEncoding()))
            ServiceSecretChange.Remove -> secrets.delete(serviceId)
            ServiceSecretChange.Keep -> if (!keepable) secrets.delete(serviceId)
        }
    }

    private fun statusOf(descriptor: ServiceDescriptor, state: ServiceCredentialState): ServiceCredentialStatus {
        val stored = state.services[descriptor.id.value]
        val origin = stored?.let { previousOrigin(descriptor, it) }
        return ServiceCredentialStatus(
            serviceId = descriptor.id,
            displayName = descriptor.displayName,
            summary = descriptor.summary,
            configured = stored != null,
            defaultBaseUrl = descriptor.defaultBaseUrl,
            baseUrl = stored?.baseUrl,
            baseUrlEditable = descriptor.baseUrlEditable,
            hasSecret = origin != null && !recovery.pending(descriptor.id) && secrets.read(descriptor.id, origin) != null,
            secretLabel = descriptor.secretLabel,
            configFields = descriptor.configFields,
            config = stored?.config.orEmpty(),
            revision = stored?.revisionName(),
            lastTest = stored?.lastTest ?: ServiceTestStatus(),
        )
    }
}

/**
 * Puts a service's secret back after a failed write. The earlier secret is read before the write; when
 * the write fails the store restores it. When that restore fails too, the earlier secret stays here as
 * the service's recovery state: the service is unusable (no saved key reported, no test, no write) until
 * a later access restores it. A secret file that cannot be decoded refuses the write before writing
 * anything, so there is nothing to restore. Prints no secret.
 */
private class SecretRecovery(private val secrets: ServiceSecretStore) {
    private val unrecovered = mutableMapOf<ServiceId, Earlier>()

    /** The secret [serviceId] had, bound to [origin]; none when either is null. */
    private class Earlier(val origin: String?, val secret: String?) {
        override fun toString(): String = "Earlier(hasSecret=${secret != null})"
    }

    /** Runs [change] on [serviceId]'s secret and settings; a failure restores the earlier secret, then rethrows. */
    fun guarded(serviceId: ServiceId, origin: String?, change: () -> Unit) {
        val earlier = Earlier(origin, origin?.let { secrets.read(serviceId, it) })
        var needsRestore = true
        try {
            change()
            needsRestore = false
        } catch (untouched: SecretFileUnreadable) {
            needsRestore = false
            throw untouched
        } finally {
            if (needsRestore && runCatching { restore(serviceId, earlier) }.isFailure) unrecovered[serviceId] = earlier
        }
    }

    /** Whether [serviceId]'s secret still awaits a restore. */
    fun pending(serviceId: ServiceId): Boolean = serviceId in unrecovered

    /** Retries a pending restore of [serviceId]'s secret; it is forgotten once it succeeds. */
    fun recover(serviceId: ServiceId) {
        val earlier = unrecovered[serviceId] ?: return
        if (runCatching { restore(serviceId, earlier) }.isSuccess) unrecovered.remove(serviceId)
    }

    /** A service whose secret cannot be restored is refused (`internal`) rather than used inconsistently. */
    fun requireRecovered(serviceId: ServiceId) {
        recover(serviceId)
        if (pending(serviceId)) serviceRefuse(ServiceCredentialErrorCode.INTERNAL)
    }

    private fun restore(serviceId: ServiceId, earlier: Earlier) {
        val origin = earlier.origin
        val secret = earlier.secret
        if (origin == null || secret == null) secrets.delete(serviceId) else secrets.write(serviceId, BoundSecret(origin, secret))
    }
}

/** The origin [stored]'s secret is bound to: of its address override, else of the service's default. */
private fun previousOrigin(descriptor: ServiceDescriptor, stored: StoredServiceCredential): String? =
    (stored.baseUrl ?: descriptor.defaultBaseUrl)?.let(ServiceAddress::originOf)

/** The revision as the wire names it (`rev-3`). */
private fun StoredServiceCredential.revisionName(): String = "rev-$revision"
