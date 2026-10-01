package com.letta.mobile.data.repository.modelcontrol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which rows a provider section lists. */
enum class ModelVisibilityFilter {
    ALL,
    SHOWN,
    HIDDEN,
    ;

    fun admits(row: CatalogRow): Boolean = when (this) {
        ALL -> true
        SHOWN -> row.exposed
        HIDDEN -> !row.exposed
    }
}

/** A section narrowed by the current query and filter. */
data class VisibleSection(val section: ProviderSection, val rows: List<CatalogRow>)

/** Screen state of the Providers & Models pane, shared by Android and desktop. */
data class ProviderManagementState(
    val sections: List<ProviderSection> = emptyList(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val message: String? = null,
    val query: String = "",
    val filter: ModelVisibilityFilter = ModelVisibilityFilter.ALL,
    /** Provider keys whose model lists are open. */
    val expanded: Set<String> = emptySet(),
    val form: ProviderConnectForm? = null,
    val pendingDisconnect: ConnectableProvider? = null,
) {
    val totalModels: Int get() = sections.sumOf { it.models.size }
    val shownModels: Int get() = sections.sumOf { it.exposedCount }
    val searching: Boolean get() = query.isNotBlank()

    /** Sections that survive the query and filter, each with only its matching rows. */
    val visible: List<VisibleSection> get() = sections.mapNotNull(::narrow)

    /** A search opens every matching section; otherwise the user's toggles decide. */
    fun isExpanded(section: ProviderSection): Boolean = searching || section.key in expanded

    private fun narrow(section: ProviderSection): VisibleSection? {
        val needle = query.trim()
        val rows = section.models.filter { filter.admits(it) && it.matches(needle) }
        return if (keeps(section, rows, needle)) VisibleSection(section, rows) else null
    }

    /**
     * A section with matching rows always stays. Without rows it stays only
     * under the All filter: always when not searching, or when the search
     * names the provider itself.
     */
    private fun keeps(section: ProviderSection, rows: List<CatalogRow>, needle: String): Boolean = when {
        rows.isNotEmpty() -> true
        filter != ModelVisibilityFilter.ALL -> false
        needle.isBlank() -> true
        else -> section.displayName.contains(needle, ignoreCase = true)
    }

    /** A blank search matches every row. */
    private fun CatalogRow.matches(needle: String): Boolean =
        needle.isBlank() ||
            handle.value.contains(needle, ignoreCase = true) ||
            model.model.displayName.contains(needle, ignoreCase = true) ||
            identity.contains(needle, ignoreCase = true)
}

/**
 * Presenter of the Providers & Models pane (letta-mobile-w4q4p.6): one state
 * composed from the provider and catalog repositories. Hosts own [scope]
 * (Android: viewModelScope; desktop: a composition scope).
 */
class ProviderManagementController(
    private val scope: CoroutineScope,
    private val providers: ProviderConnectionRepository,
    private val catalog: ModelCatalogRepository,
) {
    private val _state = MutableStateFlow(ProviderManagementState())
    val state: StateFlow<ProviderManagementState> = _state.asStateFlow()

    init {
        scope.launch {
            combine(providers.providers, catalog.models) { p, m -> ProviderCatalogComposer.compose(p, m) }
                .collect { sections -> _state.update { it.copy(sections = sections) } }
        }
    }

    /** Both listings in parallel; [force] bypasses the App Server's model cache. */
    fun refresh(force: Boolean = false) = run(loading = true, failure = "Couldn't load providers") {
        coroutineScope {
            launch { providers.refresh() }
            launch { catalog.refresh(force) }
        }
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun setFilter(filter: ModelVisibilityFilter) = _state.update { it.copy(filter = filter) }

    fun toggleExpanded(key: String) = _state.update {
        it.copy(expanded = if (key in it.expanded) it.expanded - key else it.expanded + key)
    }

    fun setExposed(change: ExposureChange) = quietly(failure = "Couldn't update ${change.handle}") {
        catalog.setExposed(change)
    }

    /** Shows or hides every model of a provider in one round trip; no-op when nothing would change. */
    fun setProviderExposed(key: String, exposed: Boolean) {
        val section = _state.value.sections.firstOrNull { it.key == key } ?: return
        val changes = section.models.filter { it.exposed != exposed }.map { ExposureChange(it.handle, exposed) }
        if (changes.isEmpty()) return
        quietly(failure = "Couldn't update ${section.displayName}") {
            catalog.setExposed(changes)
            val verb = if (exposed) "shown" else "hidden"
            _state.update { it.copy(message = "${section.displayName}: ${changes.size} models $verb") }
        }
    }

    fun openConnect(provider: ConnectableProvider) =
        _state.update { it.copy(form = ProviderConnectForm(provider), error = null) }

    fun updateForm(form: ProviderConnectForm) = _state.update { it.copy(form = form) }

    fun dismissForm() = _state.update { it.copy(form = null) }

    fun submitConnect() {
        val form = _state.value.form?.takeIf { it.canSubmit } ?: return
        run(failure = "Couldn't connect ${form.provider.displayName}") {
            val result = providers.connect(form.toRequest())
            _state.update { it.copy(form = null, message = "${form.provider.displayName} connected") }
            if (result.modelsMayHaveChanged) catalog.refresh(force = true)
        }
    }

    fun requestDisconnect(provider: ConnectableProvider) = _state.update { it.copy(pendingDisconnect = provider) }

    fun dismissDisconnect() = _state.update { it.copy(pendingDisconnect = null) }

    fun confirmDisconnect() {
        val provider = _state.value.pendingDisconnect ?: return
        _state.update { it.copy(pendingDisconnect = null) }
        run(failure = "Couldn't disconnect ${provider.displayName}") {
            val alias = provider.connections.singleOrNull()?.providerName
            val result = providers.disconnect(ProviderDisconnectTarget(ConnectableProviderId(provider.id), alias))
            _state.update { it.copy(message = "${provider.displayName} disconnected") }
            if (result.modelsMayHaveChanged) catalog.refresh(force = true)
        }
    }

    fun clearNotices() = _state.update { it.copy(error = null, message = null) }

    /** A blocking operation: the pane shows progress and disables its buttons until it ends. */
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

    /** An optimistic toggle: nothing is disabled, only a failure is reported. */
    private fun quietly(failure: String, block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "$failure: ${e.message ?: e::class.simpleName}") }
            }
        }
    }
}
