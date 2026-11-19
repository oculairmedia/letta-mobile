package com.letta.mobile.data.servicecredentials

import com.letta.mobile.data.rpcwire.SecretWriteValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the Services page shows: whether the host offers services, the services as last read, whether
 * this device may change them, the one open form, the service a request is running for, and the last
 * failure. Held in memory only; nothing of it is stored on the device.
 */
data class ServiceCredentialsState(
    /** Whether the connected host serves the contract. */
    val supported: Boolean = false,
    /** Whether a list read is running. */
    val loading: Boolean = false,
    /** Whether this device may set, test and remove credentials. */
    val canManage: Boolean = false,
    /** The services as last read. */
    val services: List<ServiceCredentialStatus> = emptyList(),
    /** The service whose form is open; null when none is. */
    val editing: ServiceId? = null,
    /** The service a write, test or removal is running for; null when none is. */
    val busy: ServiceId? = null,
    /** The last request's failure; null after a success. */
    val error: ServiceCredentialErrorCode? = null,
    /** The service [error] belongs to; null when it was the list read's. */
    val failedService: ServiceId? = null,
)

/** What a form submits: the typed address, the typed secret (blank keeps the stored one) and the settings. */
class ServiceCredentialDraft(
    /** The typed address; blank or the default uses the service's default. */
    val baseUrl: String,
    /** The typed secret; blank keeps the stored secret. Never printed. */
    val secret: String,
    /** The typed settings, by key; blank values are dropped. */
    val config: Map<String, String>,
) {
    /** The write-only command storing this draft over [status] at its revision. */
    fun commandFor(status: ServiceCredentialStatus): ServiceCredentialSetCommand {
        val address = baseUrl.trim().takeIf { status.baseUrlEditable && it.isNotEmpty() && it != status.defaultBaseUrl }
        val typed = secret.trim()
        val change = if (typed.isEmpty()) ServiceSecretChange.Keep else ServiceSecretChange.Replace(SecretWriteValue(typed))
        val settings = config.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() }
        return ServiceCredentialSetCommand(status.serviceId, address, change, settings, status.revision)
    }

    override fun toString(): String = "ServiceCredentialDraft(secretTyped=${secret.isNotBlank()}, configKeys=${config.keys})"
}

/**
 * The Services page over [client]. Requests run in [scope]; each updates [state]. A revision conflict
 * re-reads the list so the next attempt names the current revision.
 */
class ServiceCredentialsController(
    private val client: ServiceCredentialsClient,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(ServiceCredentialsState())

    /** What the page shows. */
    val state: StateFlow<ServiceCredentialsState> = mutableState.asStateFlow()

    /** Reads the list again; a host without the contract leaves the page unsupported. */
    fun refresh() {
        scope.launch { reload() }
    }

    /** Opens [serviceId]'s form (closing any other). */
    fun openEdit(serviceId: ServiceId) = mutableState.update { it.copy(editing = serviceId, error = null, failedService = null) }

    /** Closes the open form. */
    fun closeEdit() = mutableState.update { it.copy(editing = null, error = null, failedService = null) }

    /** Stores [draft] for [serviceId]; closes the form on success. */
    fun save(serviceId: ServiceId, draft: ServiceCredentialDraft) = run(serviceId, closeOnSuccess = true) { status ->
        client.set(draft.commandFor(status))
    }

    /** Tests [serviceId]'s stored credential. */
    fun test(serviceId: ServiceId) = run(serviceId, closeOnSuccess = false) { client.test(serviceId) }

    /** Removes everything stored for [serviceId]. */
    fun remove(serviceId: ServiceId) = run(serviceId, closeOnSuccess = true) { status ->
        client.remove(serviceId, status.revision)
    }

    /** Reads the list; [keep] stays as the shown failure when the read itself succeeds. */
    private suspend fun reload(keep: ServiceCredentialErrorCode? = null) {
        if (!client.supported) {
            mutableState.value = ServiceCredentialsState()
            return
        }
        mutableState.update { it.copy(supported = true, loading = true) }
        val outcome = runCatching { client.list() }
        val failure = outcome.exceptionOrNull()
        if (failure != null && failure !is ServiceCredentialException) throw failure
        val list = outcome.getOrNull()
        mutableState.update { current ->
            current.copy(
                loading = false,
                canManage = list?.canManage ?: current.canManage,
                services = list?.services ?: current.services,
                error = (failure as? ServiceCredentialException)?.code ?: keep,
                failedService = if (failure == null && keep != null) current.failedService else null,
            )
        }
    }

    private fun run(
        serviceId: ServiceId,
        closeOnSuccess: Boolean,
        request: suspend (ServiceCredentialStatus) -> ServiceCredentialStatus,
    ) {
        val status = mutableState.value.services.firstOrNull { it.serviceId == serviceId } ?: return
        if (mutableState.value.busy != null) return
        mutableState.update { it.copy(busy = serviceId, error = null, failedService = null) }
        scope.launch {
            val outcome = runCatching { request(status) }
            val failure = outcome.exceptionOrNull()
            if (failure != null && failure !is ServiceCredentialException) {
                mutableState.update { it.copy(busy = null) }
                throw failure
            }
            val updated = outcome.getOrNull()
            mutableState.update { current ->
                current.copy(
                    busy = null,
                    services = updated?.let { fresh -> current.services.map { if (it.serviceId == serviceId) fresh else it } } ?: current.services,
                    editing = if (updated != null && closeOnSuccess) null else current.editing,
                    error = (failure as? ServiceCredentialException)?.code,
                    failedService = serviceId.takeIf { failure != null },
                )
            }
            val code = (failure as? ServiceCredentialException)?.code
            if (code == ServiceCredentialErrorCode.REVISION_CONFLICT) reload(keep = code)
        }
    }
}
