package com.letta.mobile.data.repository.modelcontrol

import com.letta.mobile.data.composer.ComposerEffort
import com.letta.mobile.data.model.LlmModel
import com.letta.mobile.data.model.ModelCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The composer's model picker (letta-mobile-w4q4p.6.1), shared by Android and
 * desktop: exposed models only, grouped under the provider that serves them,
 * with a search, collapsible groups, and a refresh that re-queries the host.
 */

/** Upstream `reasoning_effort` literals, as the tag a picker row shows ("Med"). */
enum class ReasoningTier(val effort: String) {
    NONE("none"),
    MINIMAL("minimal"),
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh"),
    MAX("max"),
    ;

    companion object {
        fun of(effort: String?): ReasoningTier? {
            val wanted = effort?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.effort.equals(wanted, ignoreCase = true) }
        }
    }
}

/** One selectable model. [value] is the token the host's select callback receives. */
data class ModelPickerEntry(
    val value: String,
    val handle: ModelHandle,
    val displayName: String,
    /** The tier this row runs at, when the host says; rendered dimmed after the name. */
    val tier: ReasoningTier?,
    /** Reasoning variants the host advertises for the handle; empty when none. */
    val efforts: List<String>,
    val selected: Boolean,
)

/** Models of one provider route, under that provider's name. */
data class ModelPickerGroup(val key: String, val title: String, val entries: List<ModelPickerEntry>)

object ModelPickerCatalog {
    /**
     * Groups the EXPOSED [models] by the provider serving them, in the
     * Providers pane's order. [selectedValue] (a picker token, handle or alias)
     * marks the current model.
     */
    fun groups(
        providers: List<ConnectableProvider>,
        models: List<CatalogModel>,
        selectedValue: String?,
    ): List<ModelPickerGroup> {
        val exposed = models.filter { it.exposed }
        val llms = exposed.map { it.model }
        val selected = ModelCatalog.selectedModel(llms, selectedValue)
        return ProviderCatalogComposer.compose(providers, exposed)
            .filter { it.models.isNotEmpty() }
            .map { section ->
                ModelPickerGroup(
                    key = section.key,
                    title = section.displayName,
                    entries = section.models.map { row -> entry(row.model, llms, selected) },
                )
            }
    }

    /** Key of the "Recent" group [withRecents] puts first. */
    const val RECENTS_GROUP_KEY = "recent"
    const val RECENTS_GROUP_TITLE = "Recent"

    /**
     * letta-mobile-bzvro.18: [groups] headed by a "Recent" group of the [recent] models (newest
     * first) that the catalog still offers. A recent entry is a handle or a picker value; one the
     * catalog no longer lists is skipped. No recents, no group.
     */
    fun withRecents(groups: List<ModelPickerGroup>, recent: List<String>): List<ModelPickerGroup> {
        if (recent.isEmpty()) return groups
        val offered = groups.flatMap { it.entries }
        val entries = recent
            .mapNotNull { wanted -> offered.firstOrNull { it.handle.value == wanted || it.value == wanted } }
            .distinctBy { it.value }
        if (entries.isEmpty()) return groups
        return listOf(ModelPickerGroup(RECENTS_GROUP_KEY, RECENTS_GROUP_TITLE, entries)) + groups
    }

    /** A blank query keeps everything; otherwise rows match by name or handle, groups by title. */
    fun filter(groups: List<ModelPickerGroup>, query: String): List<ModelPickerGroup> {
        val needle = query.trim()
        if (needle.isEmpty()) return groups
        return groups.mapNotNull { group ->
            val titleMatches = group.title.contains(needle, ignoreCase = true)
            val rows = if (titleMatches) group.entries else group.entries.filter { it.matches(needle) }
            if (rows.isEmpty()) null else group.copy(entries = rows)
        }
    }

    private fun entry(model: CatalogModel, llms: List<LlmModel>, selected: LlmModel?) = ModelPickerEntry(
        value = ModelCatalog.selectionValue(llms, model.model),
        handle = model.handle,
        displayName = model.model.displayName,
        tier = ReasoningTier.of(model.reasoningEffort ?: model.model.reasoningEffort),
        efforts = ComposerEffort.sorted(model.reasoningEfforts),
        selected = selected != null && selected == model.model,
    )

    private fun ModelPickerEntry.matches(needle: String): Boolean =
        displayName.contains(needle, ignoreCase = true) || handle.value.contains(needle, ignoreCase = true)
}

/** How a picker load reads the host. */
enum class ModelLoad {
    /** The host may answer from its availability cache (opening an empty picker). */
    CACHED,

    /** The host re-queries every provider ("Refresh Models": `model.list {force: true}`). */
    REQUERY,
    ;

    val force: Boolean get() = this == REQUERY
}

/** What the picker lists and how it re-reads the host. */
interface ModelPickerSource {
    val providers: Flow<List<ConnectableProvider>>
    val models: Flow<List<CatalogModel>>

    /** True while exposure can be edited from the picker ("Edit Models…"). */
    val canEditModels: Flow<Boolean>

    /** letta-mobile-bzvro.18: recently used models (handles), newest first; they head the list. */
    val recents: Flow<List<String>> get() = flowOf(emptyList())

    suspend fun load(mode: ModelLoad)

    /** This source with [recents] heading its list. */
    fun withRecents(recents: Flow<List<String>>): ModelPickerSource = RecentsPickerSource(this, recents)

    companion object {
        /**
         * The App Server catalog with exposure: hidden models stay out of the
         * picker, a toggle in the Models sheet shows up here at once (same
         * repository), and [ModelLoad.REQUERY] is `model.list {force: true}`,
         * which bypasses the host's availability cache and re-fetches every
         * provider.
         */
        fun catalog(providers: ProviderConnectionRepository, catalog: ModelCatalogRepository): ModelPickerSource =
            CatalogPickerSource(providers, catalog)

        /**
         * A backend without the admin catalog (no exposure, no provider rows):
         * every model is listed and grouped by its route prefix.
         */
        fun of(models: Flow<List<LlmModel>>, reload: suspend (ModelLoad) -> Unit): ModelPickerSource =
            object : ModelPickerSource {
                override val providers: Flow<List<ConnectableProvider>> = flowOf(emptyList())
                override val models: Flow<List<CatalogModel>> = models.map { list ->
                    list.map { CatalogModel(model = it, exposed = true, reasoningEfforts = emptyList()) }
                }
                override val canEditModels: Flow<Boolean> = flowOf(false)

                override suspend fun load(mode: ModelLoad) = reload(mode)
            }

        /**
         * [primary] (the admin catalog) while the host answers it, else
         * [fallback] (the chat's plain model list): a backend without
         * `model.list` admin RPC still gets a working picker, without "Edit
         * Models…". A primary that answers again takes over again.
         */
        fun withFallback(primary: ModelPickerSource, fallback: ModelPickerSource): ModelPickerSource =
            FallbackPickerSource(primary, fallback)
    }
}

private class CatalogPickerSource(
    private val providerRepository: ProviderConnectionRepository,
    private val catalog: ModelCatalogRepository,
) : ModelPickerSource {
    override val providers: Flow<List<ConnectableProvider>> = providerRepository.providers
    override val models: Flow<List<CatalogModel>> = catalog.models
    override val canEditModels: Flow<Boolean> = flowOf(true)

    /** Provider rows only name the groups, so a failed `provider.list` does not fail the load. */
    override suspend fun load(mode: ModelLoad) = coroutineScope {
        launch { attempt { providerRepository.refresh() } }
        launch { catalog.refresh(mode.force) }
        Unit
    }
}

private class RecentsPickerSource(
    private val delegate: ModelPickerSource,
    override val recents: Flow<List<String>>,
) : ModelPickerSource by delegate

private class FallbackPickerSource(
    private val primary: ModelPickerSource,
    private val fallback: ModelPickerSource,
) : ModelPickerSource {
    private val primaryWorks = MutableStateFlow(true)
    override val providers: Flow<List<ConnectableProvider>> = preferPrimary(primary.providers, fallback.providers)
    override val models: Flow<List<CatalogModel>> = preferPrimary(primary.models, fallback.models)

    /** Recents name handles, which either list resolves; the primary's own store wins when it has one. */
    override val recents: Flow<List<String>> =
        combine(primary.recents, fallback.recents) { p, f -> p.ifEmpty { f } }
    override val canEditModels: Flow<Boolean> =
        combine(primaryWorks, primary.canEditModels) { works, editable -> works && editable }

    override suspend fun load(mode: ModelLoad) {
        primaryWorks.value = attempt { primary.load(mode) }
        if (!primaryWorks.value) fallback.load(mode)
    }

    private fun <T> preferPrimary(fromPrimary: Flow<T>, fromFallback: Flow<T>): Flow<T> =
        combine(primaryWorks, fromPrimary, fromFallback) { works, p, f -> if (works) p else f }
}

/** Runs [block]; false when it failed (cancellation still propagates). */
private suspend fun attempt(block: suspend () -> Unit): Boolean = try {
    block()
    true
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    false
}

/** Screen state of the model picker. */
data class ModelPickerState(
    val groups: List<ModelPickerGroup> = emptyList(),
    val query: String = "",
    /** Group keys the user folded. A search unfolds every matching group. */
    val collapsed: Set<String> = emptySet(),
    /** The first load of an empty catalog. */
    val loading: Boolean = false,
    /** A user-asked refresh ("Refresh Models") is running. */
    val refreshing: Boolean = false,
    val error: String? = null,
    val canEditModels: Boolean = false,
) {
    val searching: Boolean get() = query.isNotBlank()

    /** A load of either kind is running. */
    val busy: Boolean get() = loading || refreshing

    /** Opening on an empty catalog with nothing in flight. */
    val needsFirstLoad: Boolean get() = groups.isEmpty() && !busy

    val visible: List<ModelPickerGroup> get() = ModelPickerCatalog.filter(groups, query)

    val selected: ModelPickerEntry? get() = groups.firstNotNullOfOrNull { g -> g.entries.firstOrNull { it.selected } }

    /** The catalog's models; the "Recent" group repeats some of them and is not counted. */
    val modelCount: Int get() = groups.filter { it.key != ModelPickerCatalog.RECENTS_GROUP_KEY }.sumOf { it.entries.size }

    fun isCollapsed(group: ModelPickerGroup): Boolean = !searching && group.key in collapsed
}

/**
 * Presenter of the model picker. Hosts own [scope] and call [setSelected]
 * with the conversation's effective model (its own pick, else the agent's —
 * see [ConversationModelSelections]); selecting is the host's callback, so the
 * per-conversation override semantics stay where they are.
 */
class ModelPickerController(
    private val scope: CoroutineScope,
    private val source: ModelPickerSource,
) {
    private val selectedValue = MutableStateFlow<String?>(null)
    private val _state = MutableStateFlow(ModelPickerState())
    val state: StateFlow<ModelPickerState> = _state.asStateFlow()

    init {
        scope.launch {
            combine(source.providers, source.models, selectedValue, source.recents) { providers, models, selected, recent ->
                ModelPickerCatalog.withRecents(ModelPickerCatalog.groups(providers, models, selected), recent)
            }.collect { groups -> _state.update { it.copy(groups = groups) } }
        }
        scope.launch { source.canEditModels.collect { editable -> _state.update { it.copy(canEditModels = editable) } } }
    }

    fun setSelected(value: String?) {
        selectedValue.value = value
    }

    fun setQuery(query: String) = _state.update { it.copy(query = query) }

    fun toggleGroup(group: ModelPickerGroup) = _state.update {
        val key = group.key
        it.copy(collapsed = if (key in it.collapsed) it.collapsed - key else it.collapsed + key)
    }

    /** Loads the catalog when the picker opens on an empty one; a no-op otherwise. */
    fun ensureLoaded() {
        if (_state.value.needsFirstLoad) load(ModelLoad.CACHED)
    }

    /** "Refresh Models": re-queries the host's providers; the old list stays until the new one lands. */
    fun refresh() {
        if (!_state.value.refreshing) load(ModelLoad.REQUERY)
    }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun load(mode: ModelLoad) {
        _state.update { it.marked(mode, running = true).copy(error = null) }
        scope.launch {
            try {
                source.load(mode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = "${mode.failure}: ${e.message ?: e::class.simpleName}") }
            } finally {
                _state.update { it.marked(mode, running = false) }
            }
        }
    }

    private fun ModelPickerState.marked(mode: ModelLoad, running: Boolean): ModelPickerState = when (mode) {
        ModelLoad.CACHED -> copy(loading = running)
        ModelLoad.REQUERY -> copy(refreshing = running)
    }

    private val ModelLoad.failure: String
        get() = when (this) {
            ModelLoad.CACHED -> "Couldn't load models"
            ModelLoad.REQUERY -> "Couldn't refresh models"
        }
}
