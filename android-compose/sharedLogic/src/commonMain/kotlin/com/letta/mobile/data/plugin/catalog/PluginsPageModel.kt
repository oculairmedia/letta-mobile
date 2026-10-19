package com.letta.mobile.data.plugin.catalog

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.PluginId
import com.letta.mobile.data.plugin.PluginCapability

/** The file the owner picked to install, with the sha256 shown before anything is uploaded. */
@Immutable
data class PickedPluginPackage(val fileName: String, val sizeBytes: Int, val sha256: String)

/** One thing a staged plugin asks for that the owner allows or denies on the consent sheet. */
sealed interface PluginConsentItem {
    /** Unique across the sheet: the decision's key. */
    val key: String

    /** What the sheet shows: the capability's manifest name, the origin, the page path. */
    val label: String

    data class Capability(val capability: PluginCapability) : PluginConsentItem {
        override val key: String get() = "capability:${capability.wire}"
        override val label: String get() = capability.wire
    }

    data class Origin(val origin: String) : PluginConsentItem {
        override val key: String get() = "origin:$origin"
        override val label: String get() = origin
    }

    data class Page(val page: String) : PluginConsentItem {
        override val key: String get() = "page:$page"
        override val label: String get() = page
    }
}

/** The consent sheet: what the staged plugin asks for and what the owner decided per item (absent = undecided). */
@Immutable
data class PluginConsentSheet(
    val request: PluginInstallRequest,
    val decisions: Map<String, Boolean> = emptyMap(),
) {
    val capabilities: List<PluginConsentItem> get() = request.capabilities.map(PluginConsentItem::Capability)
    val origins: List<PluginConsentItem> get() = request.origins.map(PluginConsentItem::Origin)
    val pages: List<PluginConsentItem> get() = request.pages.map(PluginConsentItem::Page)
    val items: List<PluginConsentItem> get() = capabilities + origins + pages

    fun decision(item: PluginConsentItem): Boolean? = decisions[item.key]

    /** Whether every item has an explicit Allow or Deny: only then can Install be pressed. */
    val allDecided: Boolean get() = items.all { it.key in decisions }

    val denied: List<PluginConsentItem> get() = items.filter { decisions[it.key] == false }
}

/** What pressing Install on [PluginConsentSheet] amounts to. */
sealed interface PluginConsentVerdict {
    /** Some item has no decision yet. */
    data object Undecided : PluginConsentVerdict

    /** Something was denied: a plugin installs only with everything it asks for, so nothing is installed. */
    data class Refused(val reason: String) : PluginConsentVerdict

    data class Granted(val grants: PluginGrants) : PluginConsentVerdict
}

/** What the Plugins page shows. */
@Immutable
data class PluginsPageState(
    val plugins: List<PluginSummary> = emptyList(),
    val picked: PickedPluginPackage? = null,
    val consent: PluginConsentSheet? = null,
    /** A catalog call is in flight; the page's actions wait for it. */
    val busy: Boolean = false,
    val error: String? = null,
    val confirmDisableAll: Boolean = false,
    /** The plugin whose removal waits for the owner's confirmation. */
    val pendingRemove: PluginId? = null,
    /** This device's "Live plugin views" switch. */
    val liveViews: Boolean = false,
) {
    val anyEnabled: Boolean get() = plugins.any { it.enabled }
}

/** Everything that changes [PluginsPageState]; each event knows its own effect. */
sealed interface PluginsPageEvent {
    fun applyTo(state: PluginsPageState): PluginsPageState

    data class Listed(val plugins: List<PluginSummary>) : PluginsPageEvent {
        override fun applyTo(state: PluginsPageState) = state.copy(plugins = plugins)
    }

    data class Picked(val pkg: PickedPluginPackage) : PluginsPageEvent {
        override fun applyTo(state: PluginsPageState) = state.copy(picked = pkg, error = null)
    }

    data object PickDiscarded : PluginsPageEvent {
        override fun applyTo(state: PluginsPageState) = state.copy(picked = null)
    }

    /** A catalog call started: the page waits for it. */
    data object Started : PluginsPageEvent {
        override fun applyTo(state: PluginsPageState) = state.copy(busy = true, error = null)
    }

    /** A catalog call finished: any confirmation it answered closes. */
    data object Done : PluginsPageEvent {
        override fun applyTo(state: PluginsPageState) = state.copy(busy = false, confirmDisableAll = false, pendingRemove = null)
    }

    data class Failed(val reason: String) : PluginsPageEvent {
        override fun applyTo(state: PluginsPageState) =
            state.copy(busy = false, error = reason, confirmDisableAll = false, pendingRemove = null)
    }

    /** The host staged the picked package: the consent sheet opens on what it asks for. */
    data class Staged(val request: PluginInstallRequest) : PluginsPageEvent {
        override fun applyTo(state: PluginsPageState) = state.copy(busy = false, picked = null, consent = PluginConsentSheet(request))
    }

    data class Decided(val item: PluginConsentItem, val allow: Boolean) : PluginsPageEvent {
        override fun applyTo(state: PluginsPageState) = state.copy(consent = state.consent?.decide(item, allow))
    }

    data object ConsentClosed : PluginsPageEvent {
        override fun applyTo(state: PluginsPageState) = state.copy(busy = false, consent = null)
    }

    /** Nothing was installed: the sheet and the picked file go, the reason stays on the page. */
    data class InstallRefused(val reason: String) : PluginsPageEvent {
        override fun applyTo(state: PluginsPageState) = state.copy(busy = false, consent = null, picked = null, error = reason)
    }

    data object ErrorDismissed : PluginsPageEvent {
        override fun applyTo(state: PluginsPageState) = state.copy(error = null)
    }

    data class DisableAllAsked(val open: Boolean) : PluginsPageEvent {
        override fun applyTo(state: PluginsPageState) = state.copy(confirmDisableAll = open)
    }

    data class RemoveAsked(val id: PluginId?) : PluginsPageEvent {
        override fun applyTo(state: PluginsPageState) = state.copy(pendingRemove = id)
    }

    data class LiveViews(val on: Boolean) : PluginsPageEvent {
        override fun applyTo(state: PluginsPageState) = state.copy(liveViews = on)
    }
}

/** An item's decision, recorded only when the sheet asks for that item. */
internal fun PluginConsentSheet.decide(item: PluginConsentItem, allow: Boolean): PluginConsentSheet =
    if (items.any { it.key == item.key }) copy(decisions = decisions + (item.key to allow)) else this

/** The Plugins page's pure state machine; [PluginsPageController] feeds it. */
object PluginsPageModel {
    fun reduce(state: PluginsPageState, event: PluginsPageEvent): PluginsPageState = event.applyTo(state)

    /** What Install means for [sheet]: wait for every decision, refuse on any Deny, else grant exactly what was asked. */
    fun verdict(sheet: PluginConsentSheet): PluginConsentVerdict {
        if (!sheet.allDecided) return PluginConsentVerdict.Undecided
        val denied = sheet.denied
        if (denied.isNotEmpty()) return PluginConsentVerdict.Refused(refusal(sheet.request, denied))
        val request = sheet.request
        return PluginConsentVerdict.Granted(PluginGrants(request.capabilities.toSet(), request.origins.toSet(), request.pages.toSet()))
    }

    private fun refusal(request: PluginInstallRequest, denied: List<PluginConsentItem>): String =
        "Install refused: you denied ${denied.joinToString { it.label }}. ${request.name} installs only with everything it asks for, so nothing was installed."
}
