package com.letta.mobile.data.repository.modelcontrol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Screen state for provider management, shared by the Android and desktop panes. */
data class ProviderAdminState(
    val providers: List<ConnectableProvider> = emptyList(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val form: ProviderConnectForm? = null,
    val pendingDisconnect: ConnectableProvider? = null,
) {
    /** Connected providers first, then alphabetical. */
    val sortedProviders: List<ConnectableProvider>
        get() = providers.sortedWith(compareBy({ !it.isConnected }, { it.displayName.lowercase() }))
}

/**
 * Platform-neutral presenter for provider management (letta-mobile-w4q4p).
 * Hosts own [scope] (Android: viewModelScope; desktop: a composition scope).
 */
class ProviderAdminController(
    private val scope: CoroutineScope,
    private val repository: ProviderConnectionRepository,
) {
    private val _state = MutableStateFlow(ProviderAdminState())
    val state: StateFlow<ProviderAdminState> = _state.asStateFlow()

    fun refresh() = run(loading = true, failure = "Couldn't load providers") {
        val providers = repository.refresh()
        _state.update { it.copy(providers = providers) }
    }

    fun openConnect(provider: ConnectableProvider) = _state.update { it.copy(form = ProviderConnectForm(provider), error = null) }

    fun updateForm(form: ProviderConnectForm) = _state.update { it.copy(form = form) }

    fun dismissForm() = _state.update { it.copy(form = null) }

    fun submitConnect() {
        val form = _state.value.form?.takeIf { it.canSubmit } ?: return
        run(failure = "Couldn't connect ${form.provider.displayName}") {
            val result = repository.connect(form.toRequest())
            _state.update { it.copy(providers = result.providers, form = null, message = "${form.provider.displayName} connected") }
        }
    }

    fun requestDisconnect(provider: ConnectableProvider) = _state.update { it.copy(pendingDisconnect = provider) }

    fun dismissDisconnect() = _state.update { it.copy(pendingDisconnect = null) }

    fun confirmDisconnect() {
        val provider = _state.value.pendingDisconnect ?: return
        _state.update { it.copy(pendingDisconnect = null) }
        run(failure = "Couldn't disconnect ${provider.displayName}") {
            val alias = provider.connections.singleOrNull()?.providerName
            val result = repository.disconnect(provider.id, alias)
            _state.update { it.copy(providers = result.providers, message = "${provider.displayName} disconnected") }
        }
    }

    fun clearNotices() = _state.update { it.copy(error = null, message = null) }

    private fun run(loading: Boolean = false, failure: String, block: suspend () -> Unit) {
        _state.update { it.copy(loading = loading, busy = !loading, error = null, message = null) }
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "$failure: ${e.message ?: e::class.simpleName}") }
            } finally {
                _state.update { it.copy(loading = false, busy = false) }
            }
        }
    }
}

/** Screen state for model exposure: which of the host's models the pickers show. */
data class ModelExposureState(
    val models: List<CatalogModel> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val query: String = "",
) {
    private val matching: List<CatalogModel>
        get() = models.filter { query.isBlank() || it.handle.contains(query.trim(), ignoreCase = true) }

    val exposed: List<CatalogModel> get() = matching.filter { it.exposed }.sortedBy { it.handle }

    val hidden: List<CatalogModel> get() = matching.filterNot { it.exposed }.sortedBy { it.handle }
}

/** Platform-neutral presenter for per-model exposure toggles (letta-mobile-w4q4p). */
class ModelExposureController(
    private val scope: CoroutineScope,
    private val repository: ModelCatalogRepository,
) {
    private val _state = MutableStateFlow(ModelExposureState())
    val state: StateFlow<ModelExposureState> = _state.asStateFlow()

    init {
        scope.launch { repository.models.collect { models -> _state.update { it.copy(models = models) } } }
    }

    fun refresh(force: Boolean = false) {
        _state.update { it.copy(loading = true, error = null) }
        scope.launch {
            val failure = runCatching { repository.refresh(force) }.exceptionOrNull()
            _state.update { it.copy(loading = false, error = failure?.let { e -> "Couldn't load models: ${e.message}" }) }
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun setExposed(handle: String, exposed: Boolean) {
        scope.launch {
            val failure = runCatching { repository.setExposed(handle, exposed) }.exceptionOrNull()
            failure?.let { e -> _state.update { it.copy(error = "Couldn't update $handle: ${e.message}") } }
        }
    }
}
