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
        val read = attempt { client.list() }
        mutableState.update { current -> current.afterRead(read, keep) }
        read.escaped?.let { throw it }
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
            val done = attempt { request(status) }
            mutableState.update { current -> current.afterRequest(serviceId, done, closeOnSuccess) }
            done.escaped?.let { throw it }
            if (done.failure == ServiceCredentialErrorCode.REVISION_CONFLICT) reload(keep = done.failure)
        }
    }
}

/**
 * What a request returned: its [value], or the [failure] code a refused request ended with, or the
 * [escaped] exception that is not a refusal (cancellation included), which the caller rethrows once the
 * state no longer shows the request as running.
 */
private class Attempt<T>(val value: T?, val failure: ServiceCredentialErrorCode?, val escaped: Throwable?)

/** Runs [request], keeping a refusal as its failure code and any other throwable as [Attempt.escaped]. */
private suspend fun <T> attempt(request: suspend () -> T): Attempt<T> {
    val outcome = runCatching { request() }
    val thrown = outcome.exceptionOrNull()
    val refusal = thrown as? ServiceCredentialException
    return Attempt(outcome.getOrNull(), refusal?.code, thrown.takeIf { refusal == null })
}

/** This state after a list read: the list and rights it brought, and the failure to show ([keep] after a good read). */
private fun ServiceCredentialsState.afterRead(
    read: Attempt<ServiceCredentialList>,
    keep: ServiceCredentialErrorCode?,
): ServiceCredentialsState {
    val list = read.value
    return copy(
        loading = false,
        canManage = list?.canManage ?: canManage,
        services = list?.services ?: services,
        error = read.failure ?: keep,
        failedService = if (read.failure == null && keep != null) failedService else null,
    )
}

/** This state after a write, test or removal for [serviceId] ended as [done]. */
private fun ServiceCredentialsState.afterRequest(
    serviceId: ServiceId,
    done: Attempt<ServiceCredentialStatus>,
    closeOnSuccess: Boolean,
): ServiceCredentialsState {
    val fresh = done.value
    return copy(
        busy = null,
        services = fresh?.let(services::replacing) ?: services,
        editing = if (fresh != null && closeOnSuccess) null else editing,
        error = done.failure,
        failedService = serviceId.takeIf { done.failure != null },
    )
}

/** This list with [fresh] in place of the status of the same service. */
private fun List<ServiceCredentialStatus>.replacing(fresh: ServiceCredentialStatus): List<ServiceCredentialStatus> =
    map { if (it.serviceId == fresh.serviceId) fresh else it }
