package com.letta.mobile.data.controller.node.iroh

import com.letta.mobile.data.transport.appserver.AppServerInboundFrame
import com.letta.mobile.data.transport.appserver.AppServerReceivedFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Where a catalog snapshot came from. Only [DeviceStatus] and [HostEnumeration]
 * are authoritative enumerations; [None] means we have never seen one.
 */
enum class SkillCatalogOrigin(val wireName: String) {
    /** No authoritative enumeration observed. `skill.list` must not claim a catalog. */
    None("unavailable"),

    /** A non-empty `device_status.current_available_skills` array. */
    DeviceStatus("device_status"),

    /** An injected host adapter that enumerated the configured skill roots. */
    HostEnumeration("host_enumeration"),
}

/**
 * Controller-side projection of skill availability.
 *
 * ## Wire reality
 *
 * - `skills_updated` (`emitSkillsUpdated`) is exactly `{ type, timestamp }`.
 * - There is no `skill_list` command; the only skill verbs are `skill_enable` /
 *   `skill_disable` (filesystem symlinks under `<LETTA_HOME>/skills`).
 * - `device_status.current_available_skills` was a hard-coded `[]` in 0.29.12; from
 *   0.34 it is the conversation runtime's discovered skills
 *   (`{id, name, description, path, source}` for the bundled, global, agent and
 *   project sources), and still `[]` before a turn.
 *
 * ## Model (letta-mobile skills-honest-controls)
 *
 * Every entry carries `source` and an explicit `controllable` flag. Only the host
 * enumeration of letta-code's global control root ([HostSkillsEnumerator], read on
 * every listing through [hostEnumeration]) can mark a skill controllable, and only a
 * symlink there, because that is the one thing `skill_disable` can undo.
 *
 * Skills the App Server advertises from its other sources (bundled, agent, project)
 * are merged in READ-ONLY: letta-code has no command that enables or disables them.
 * They never replace the host enumeration — before this, a populated device status
 * overwrote it with entries that had no controllability at all, so the client
 * defaulted every skill to removable and offered toggles that could only fail.
 * Global-source advertised entries are dropped while a host enumeration exists: the
 * host reads that source directly.
 *
 * Without a host enumeration, a populated device status still hydrates the catalog
 * (everything read-only); a hard-coded empty one never does.
 */
class NativeSkillsCatalog(
    /** Re-reads the host's global skill root; null when the host cannot enumerate. */
    private val hostEnumeration: (() -> JsonArray?)? = null,
) {
    private val lock = Any()

    @Volatile
    private var hostSkills: JsonArray? = null

    @Volatile
    private var advertised: JsonArray? = null

    @Volatile
    private var stale: Boolean = false

    /** True only once an authoritative enumeration has been observed. */
    fun isHydrated(): Boolean = origin() != SkillCatalogOrigin.None

    /** True when a `skills_updated` invalidation arrived that could not be re-read yet. */
    fun isStale(): Boolean = stale

    fun origin(): SkillCatalogOrigin = when {
        hostSkills != null -> SkillCatalogOrigin.HostEnumeration
        !advertised.isNullOrEmpty() -> SkillCatalogOrigin.DeviceStatus
        else -> SkillCatalogOrigin.None
    }

    /** Host entries first, then each advertised read-only entry the host did not list. */
    fun snapshot(): JsonArray {
        val host = hostSkills.orEmpty()
        val names = host.mapNotNullTo(HashSet(), ::nameOf)
        val extras = advertised.orEmpty().filter { entry ->
            nameOf(entry) !in names && (hostSkills == null || sourceOf(entry) != HostSkillsEnumerator.GLOBAL_SOURCE)
        }
        return JsonArray(host + extras)
    }

    /** Wire shape expected by Iroh admin_rpc skill list clients. */
    fun listEnvelope(): JsonObject =
        buildJsonObject {
            put("skills", snapshot())
            put("hydrated", isHydrated())
            put("stale", isStale())
            put("catalog_source", origin().wireName)
        }

    fun ingest(frame: AppServerInboundFrame) {
        when (frame) {
            is AppServerInboundFrame.UpdateDeviceStatus -> {
                val next = extractSkills(frame.deviceStatus["current_available_skills"])
                // A hard-coded empty array is not an enumeration.
                if (!next.isNullOrEmpty()) advertised = next
            }

            is AppServerInboundFrame.SkillsUpdated,
            is AppServerInboundFrame.SkillEnableResponse,
            is AppServerInboundFrame.SkillDisableResponse,
            -> onSkillsChanged()

            is AppServerInboundFrame.Unknown -> {
                // Defensive: an envelope variant we failed to type is still an
                // invalidation signal, never a snapshot source.
                if (frame.type == "skills_updated") onSkillsChanged()
            }

            else -> Unit
        }
    }

    fun ingestReceived(received: AppServerReceivedFrame) = ingest(received.frame)

    fun start(scope: CoroutineScope, events: Flow<AppServerReceivedFrame>): Job =
        scope.launch {
            events.collect { ingestReceived(it) }
        }

    /**
     * Install an authoritative enumeration produced by a host-side skill-root
     * adapter. Tests and hosts without a live [hostEnumeration] use this directly.
     */
    fun hydrateFromHost(next: JsonArray) {
        synchronized(lock) {
            hostSkills = JsonArray(next.map(::normalizeSkillEntry))
            stale = false
        }
    }

    /** Re-reads the host root when one is wired; returns false when there is none or it is unreadable. */
    fun refreshFromHost(): Boolean {
        val next = hostEnumeration?.invoke() ?: return false
        hydrateFromHost(next)
        return true
    }

    /** Adapt this catalog to the [SkillsListingSource] contract admin handlers consume. */
    fun asListingSource(): SkillsListingSource = object : SkillsListingSource {
        // The filesystem is the truth (a symlink can be added or removed on disk at any
        // time), so every listing re-reads it when the host can.
        override fun currentSkills(): JsonArray {
            refreshFromHost()
            return snapshot()
        }

        override fun isHydrated(): Boolean = this@NativeSkillsCatalog.isHydrated()

        override fun isStale(): Boolean = this@NativeSkillsCatalog.isStale()

        override fun catalogSource(): String = origin().wireName
    }

    /** `skills_updated` and enable / disable results say "something changed": re-read, else mark stale. */
    private fun onSkillsChanged() {
        if (refreshFromHost()) return
        synchronized(lock) {
            if (isHydrated()) stale = true
        }
    }

    private fun nameOf(element: JsonElement): String? = stringField(element, "name")

    private fun sourceOf(element: JsonElement): String? = stringField(element, "source")

    private fun stringField(element: JsonElement, key: String): String? =
        (element as? JsonObject)?.get(key)?.let { (it as? JsonPrimitive)?.contentOrNull }

    private fun extractSkills(element: JsonElement?): JsonArray? {
        val entries = when (element) {
            is JsonArray -> element
            is JsonObject -> element["skills"] as? JsonArray
            else -> null
        } ?: return null
        return JsonArray(entries.map(::advertisedEntry))
    }

    /**
     * An App Server-advertised skill, read-only: letta-code addresses it by `id`
     * (falling back to `name`), and nothing on the wire can enable or disable it.
     */
    private fun advertisedEntry(element: JsonElement): JsonElement {
        val name = stringField(element, "id") ?: stringField(element, "name")
            ?: (element as? JsonPrimitive)?.contentOrNull ?: element.toString()
        return buildJsonObject {
            put("name", name)
            stringField(element, "description")?.let { put("description", it) }
            stringField(element, "path")?.let { put("skill_path", it) }
            put("source", sourceOf(element) ?: APP_SERVER_SOURCE)
            put("controllable", false)
        }
    }

    private fun normalizeSkillEntry(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> element
            is JsonPrimitive -> buildJsonObject {
                put("name", element.contentOrNull ?: element.toString())
            }

            else -> buildJsonObject { put("name", element.toString()) }
        }

    private companion object {
        /** Source label for an advertised skill that names none. */
        const val APP_SERVER_SOURCE = "app_server"
    }
}

/**
 * Injectable authoritative listing source for [SkillAdminHandlers].
 *
 * Defaults fail closed: an implementation must positively assert hydration.
 * A source that cannot enumerate must leave [isHydrated] false so `skill.list`
 * reports `capability_unavailable` instead of an invented empty catalog.
 */
interface SkillsListingSource {
    fun currentSkills(): JsonArray?

    /** False until an authoritative enumeration has been observed. */
    fun isHydrated(): Boolean = false

    /** True when an invalidation arrived after the last enumeration. */
    fun isStale(): Boolean = false

    /** Provenance of the current snapshot, for admin diagnostics. */
    fun catalogSource(): String =
        if (isHydrated()) SkillCatalogOrigin.HostEnumeration.wireName else SkillCatalogOrigin.None.wireName
}
