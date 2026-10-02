package com.letta.mobile.data.repository.modelcontrol

import com.letta.mobile.data.model.ModelCatalogNormalizer

/**
 * The management screen's view of the host (letta-mobile-w4q4p): every
 * provider the App Server can connect, joined to the catalog models that route
 * through it. Pure functions over the two repositories' snapshots; the
 * controller recomputes on every change.
 */

/** One catalog model as a provider section lists it. */
data class CatalogRow(
    val model: CatalogModel,
    /** The model behind the route, provider-agnostic (e.g. `claude-fable-5-1`). */
    val identity: String,
    /** Other provider keys serving the same identity, in section order. */
    val alsoVia: List<String>,
) {
    val handle: ModelHandle get() = model.handle
    val exposed: Boolean get() = model.exposed
}

/**
 * A provider with its models. [provider] is null when models route through a
 * provider key `provider.list` does not describe (the host still serves them).
 */
data class ProviderSection(
    /** Route key: the handle prefix (`lmstudio`), lowercase. */
    val key: String,
    val provider: ConnectableProvider?,
    val models: List<CatalogRow>,
) {
    val displayName: String get() = provider?.displayName ?: key
    val isConnected: Boolean get() = provider?.isConnected ?: models.isNotEmpty()
    val exposedCount: Int get() = models.count { it.exposed }
    val hiddenCount: Int get() = models.size - exposedCount
    val allExposed: Boolean get() = hiddenCount == 0
    val noneExposed: Boolean get() = models.isNotEmpty() && exposedCount == 0
}

object ProviderCatalogComposer {
    /**
     * Joins [providers] and [models] into sections: connected providers first
     * (most models first), then the rest alphabetically. A provider without
     * models still gets a section so it can be connected from the screen.
     */
    fun compose(providers: List<ConnectableProvider>, models: List<CatalogModel>): List<ProviderSection> {
        val byKey = models.groupBy(::routeKey)
        val claimed = mutableSetOf<ConnectableProvider>()
        val fromModels = byKey.map { (key, routed) ->
            val provider = providerFor(key, providers - claimed)?.also { claimed += it }
            ProviderSection(key, provider, rows(routed, byKey))
        }
        val withoutModels = providers.filter { it !in claimed }.map { ProviderSection(sectionKey(it), it, emptyList()) }
        return (fromModels + withoutModels).sortedWith(SECTION_ORDER)
    }

    /** The provider a model routes through: its handle prefix, else its provider type. */
    fun routeKey(model: CatalogModel): String =
        ModelCatalogNormalizer.providerPrefix(model.handle.value).ifBlank { model.model.providerType.lowercase() }

    /** The route-independent model name used to spot the same model on several providers. */
    fun identity(model: CatalogModel): String =
        ModelCatalogNormalizer.underlyingModelId(model.handle.value).lowercase()

    /** Every name a provider row answers to: id, type, name and connected aliases. */
    fun aliases(provider: ConnectableProvider): Set<String> = buildSet {
        add(provider.id)
        add(provider.providerName)
        add(provider.providerType)
        provider.connections.forEach { c ->
            c.providerName?.let(::add)
            c.providerType?.let(::add)
        }
    }.map { it.lowercase() }.filter { it.isNotBlank() }.toSet()

    /**
     * The row for [key]: an exact id match wins, then a connected row with the
     * alias, then any row with it. Two rows may share an alias (API-key and
     * OAuth Anthropic); the connected one is the one serving the models.
     */
    private fun providerFor(key: String, candidates: List<ConnectableProvider>): ConnectableProvider? {
        val matching = candidates.filter { key in aliases(it) }
        return matching.firstOrNull { it.id.lowercase() == key }
            ?: matching.firstOrNull { it.isConnected }
            ?: matching.firstOrNull()
    }

    /** A row serving no models is keyed by its id: unique even when it shares an alias with a serving row. */
    private fun sectionKey(provider: ConnectableProvider): String = provider.id.lowercase()

    private fun rows(routed: List<CatalogModel>, byKey: Map<String, List<CatalogModel>>): List<CatalogRow> =
        routed.sortedBy { it.model.displayName.lowercase() }.map { model ->
            val identity = identity(model)
            val here = routeKey(model)
            val elsewhere = byKey.keys.filter { key -> key != here && byKey.getValue(key).any { identity(it) == identity } }
            CatalogRow(model, identity, elsewhere.sorted())
        }

    private val SECTION_ORDER: Comparator<ProviderSection> =
        compareBy<ProviderSection>({ !it.isConnected }, { -it.models.size }, { it.displayName.lowercase() })
}
