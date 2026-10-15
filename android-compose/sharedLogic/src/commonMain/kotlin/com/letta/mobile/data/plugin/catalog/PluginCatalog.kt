package com.letta.mobile.data.plugin.catalog

import androidx.compose.runtime.Immutable
import com.letta.mobile.data.model.PluginId
import com.letta.mobile.data.plugin.PluginCapability
import com.letta.mobile.data.plugin.PluginHealth
import com.letta.mobile.data.plugin.PluginManifest
import com.letta.mobile.data.plugin.PluginState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * letta-mobile-cdgpr.9: what the Plugins page reads and asks of the host's plugin registry. The
 * real one (C7, letta-mobile-cdgpr.7) rides admin_rpc `plugins.*`; until it lands every client binds
 * [UnavailablePluginCatalog].
 *
 * Installing is two steps, so the owner sees what a package asks for before anything runs:
 * [install] uploads the picked file (pinned by its sha256) and the host answers with what the
 * package's manifest asks for; [consent] then grants exactly that, or the host refuses it.
 * Every call answers [PluginCatalogResult.Refused] with the host's reason instead of throwing.
 */
interface PluginCatalog {
    /** Every installed plugin, in install order; kept current by the catalog (health included). */
    val plugins: StateFlow<List<PluginSummary>>

    /** Uploads a package and stages it; the answer is what its manifest asks the owner to consent to. */
    suspend fun install(fileBytes: ByteArray, sha256: String): PluginCatalogResult<PluginInstallRequest>

    /** Grants consent to the staged plugin [id]; the host installs it only when [grants] cover everything it asks for. */
    suspend fun consent(id: PluginId, grants: PluginGrants): PluginCatalogResult<Unit>

    /** Drops the staged package of [id] without installing it (the owner cancelled or denied). */
    suspend fun cancelInstall(id: PluginId): PluginCatalogResult<Unit>

    suspend fun enable(id: PluginId): PluginCatalogResult<Unit>

    suspend fun disable(id: PluginId): PluginCatalogResult<Unit>

    suspend fun remove(id: PluginId): PluginCatalogResult<Unit>

    /** The host-wide kill switch: every plugin off at once. */
    suspend fun disableAll(): PluginCatalogResult<Unit>

    /** Asks [id] for its health now; the answer also lands in [plugins]. */
    suspend fun health(id: PluginId): PluginCatalogResult<PluginHealth>
}

/** A package file the owner picked: its name and every byte. */
class PluginPackageFile(val name: String, val bytes: ByteArray)

/** A catalog call's outcome: done, or refused with the reason to show. */
sealed interface PluginCatalogResult<out T> {
    data class Ok<T>(val value: T) : PluginCatalogResult<T>

    data class Refused(val reason: String) : PluginCatalogResult<Nothing>
}

/** One installed plugin as the Plugins page lists it. */
@Immutable
data class PluginSummary(
    val id: PluginId,
    val name: String,
    val version: String,
    val state: PluginState,
    val health: PluginHealth = PluginHealth.Unknown,
    /** The last fault the host recorded (a failed update, a crash), shown even after the plugin recovered. */
    val lastFault: String? = null,
    /** The installed version's manifest, when the host sent it (letta-mobile-cdgpr.8): its element kinds and pages. */
    val manifest: PluginManifest? = null,
) {
    /** Whether the plugin is switched on (its tools advertised). */
    val enabled: Boolean get() = state.advertises

    /** Why the plugin is faulted, or its health failed; null while it is fine. */
    val faultReason: String?
        get() = (state as? PluginState.Faulted)?.reason ?: (health as? PluginHealth.Failed)?.reason
}

/**
 * What a staged package asks for, from its manifest: the consent sheet lists every capability,
 * network origin and page, with the package hash the host verified.
 */
@Immutable
data class PluginInstallRequest(
    val id: PluginId,
    val name: String,
    val version: String,
    val publisher: String,
    val sha256: String,
    val runtimeKind: String,
    val capabilities: List<PluginCapability> = emptyList(),
    val origins: List<String> = emptyList(),
    val pages: List<String> = emptyList(),
)

/** What the owner granted a staged plugin. Pages ride `ui:pages`; they are listed so the owner sees each one. */
@Immutable
data class PluginGrants(
    val capabilities: Set<PluginCapability>,
    val origins: Set<String>,
    val pages: Set<String>,
)

/** The catalog of a backend that offers no plugins (every client until C7's admin_rpc catalog lands). */
class UnavailablePluginCatalog : PluginCatalog {
    companion object {
        const val REASON = "This backend does not offer plugins yet."
    }

    override val plugins: StateFlow<List<PluginSummary>> = MutableStateFlow(emptyList())

    override suspend fun install(fileBytes: ByteArray, sha256: String) = PluginCatalogResult.Refused(REASON)

    override suspend fun consent(id: PluginId, grants: PluginGrants) = PluginCatalogResult.Refused(REASON)

    override suspend fun cancelInstall(id: PluginId) = PluginCatalogResult.Ok(Unit)

    override suspend fun enable(id: PluginId) = PluginCatalogResult.Refused(REASON)

    override suspend fun disable(id: PluginId) = PluginCatalogResult.Refused(REASON)

    override suspend fun remove(id: PluginId) = PluginCatalogResult.Refused(REASON)

    override suspend fun disableAll() = PluginCatalogResult.Refused(REASON)

    override suspend fun health(id: PluginId) = PluginCatalogResult.Refused(REASON)
}
