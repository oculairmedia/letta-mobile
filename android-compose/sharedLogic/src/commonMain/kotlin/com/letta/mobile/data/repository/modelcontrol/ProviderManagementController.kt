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
    /** A subscription provider whose terminal sign-in steps are showing (no in-app sign-in for it). */
    val signIn: ConnectableProvider? = null,
    /** The in-app subscription sign-in (letta-mobile-zxk2n.3); Idle when none is open. */
    val oauth: ProviderSignInState = ProviderSignInState.Idle,
    /** True when this client can run subscription sign-ins itself; otherwise they take the terminal steps. */
    val inAppSignIn: Boolean = false,
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
 *
 * With [oauthHttp] (letta-mobile-zxk2n.3) subscription rows the app knows
 * sign in from the app; without it, or for rows it does not know, they show
 * the host-terminal steps.
 */
class ProviderManagementController(
    private val scope: CoroutineScope,
    private val providers: ProviderConnectionRepository,
    private val catalog: ModelCatalogRepository,
    oauthHttp: ProviderOAuthHttp? = null,
) {
    /** The in-app sign-in, when this host can run one. */
    val signIn: ProviderSignInController? = oauthHttp?.let { ProviderSignInController(scope, it, providers, catalog) }

    private val _state = MutableStateFlow(ProviderManagementState(inAppSignIn = signIn != null))
    val state: StateFlow<ProviderManagementState> = _state.asStateFlow()

    init {
        scope.launch {
            combine(providers.providers, catalog.models) { p, m -> ProviderCatalogComposer.compose(p, m) }
                .collect { sections -> _state.update { it.copy(sections = sections) } }
        }
        signIn?.let { flow ->
            scope.launch { flow.state.collect { oauth -> _state.update { it.copy(oauth = oauth) } } }
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

    /** Re-opens a connected provider's form; an endpoint keeps its base URL (saved keys are never returned). */
    fun openEdit(provider: ConnectableProvider) =
        _state.update { it.copy(form = providerEditForm(provider), error = null) }

    /**
     * Connects a subscription provider: in the app when it can run the
     * provider's sign-in, otherwise the host-terminal steps.
     */
    fun openSignIn(provider: ConnectableProvider) {
        _state.update { it.copy(error = null, message = null) }
        val flow = signIn?.takeIf { it.supports(provider) }
        if (flow != null) flow.start(provider) else _state.update { it.copy(signIn = provider) }
    }

    fun dismissSignIn() = _state.update { it.copy(signIn = null) }

    /**
     * "Check again" after a terminal sign-in: re-lists providers and, once the
     * account shows as connected, closes the steps and re-queries the models
     * it brought.
     */
    fun checkSignIn() {
        val provider = _state.value.signIn ?: return
        run(failure = "Couldn't check ${provider.displayName}") {
            val connected = providers.refresh().firstOrNull { it.id == provider.id }?.isConnected == true
            if (connected) {
                _state.update { it.copy(signIn = null, message = "${provider.displayName} connected") }
                catalog.refresh(force = true)
            } else {
                _state.update {
                    it.copy(message = "${provider.displayName} isn't connected yet. Finish /connect in the terminal, then check again.")
                }
            }
        }
    }

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
