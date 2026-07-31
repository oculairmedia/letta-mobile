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
 * ## Wire reality (verified against `@letta-ai/letta-code` 0.29.12, `letta.js`)
 *
 * - `skills_updated` is emitted by `emitSkillsUpdated`
 *   (`// src/websocket/listener/commands/skills-agents.ts`) as exactly
 *   `{ type: "skills_updated", timestamp }`. It carries **no** skill array.
 * - `device_status.current_available_skills` is the literal `[]` in *both*
 *   branches of `buildDeviceStatus` — the online branch and the no-listener
 *   fallback. It is a hard-coded empty array, not an observation.
 * - There is no `skill_list` command. The only skill verbs on the wire are
 *   `skill_enable` / `skill_disable` and their responses.
 * - `runtime_start.skill_sources` is request-only; `runtime_start_response`
 *   never echoes it.
 *
 * ## Consequence
 *
 * The App Server advertises **no authoritative skill enumeration**. Treating a
 * hard-coded `[]` as a snapshot is what made `skill.list` settle at
 * `hydrated=true` with an empty catalog while enabled skills existed.
 *
 * This catalog therefore hydrates only from a genuine enumeration:
 * - a *non-empty* `current_available_skills` array (forward-compatible with a
 *   server that actually populates it), or
 * - [hydrateFromHost], for an injected host adapter that enumerates skill roots.
 *
 * `skills_updated` is treated strictly as invalidation ([isStale]), and
 * `skill_enable_response` / `skill_disable_response` are applied as authoritative
 * *deltas* on top of an existing enumeration — a single observed mutation is not
 * itself an enumeration and never hydrates the catalog.
 *
 * ### Known limitation
 *
 * Against stock 0.29.12 this catalog stays unhydrated, and `skill.list` answers
 * `capability_unavailable` rather than a fabricated empty list. Restoring a real
 * listing requires a host-side skill-root enumerator wired into
 * [hydrateFromHost]; that adapter is out of scope here and is tracked separately.
 */
class NativeSkillsCatalog {
    private val lock = Any()

    @Volatile
    private var skills: JsonArray = JsonArray(emptyList())

    @Volatile
    private var origin: SkillCatalogOrigin = SkillCatalogOrigin.None

    @Volatile
    private var stale: Boolean = false

    /** True only once an authoritative enumeration has been observed. */
    fun isHydrated(): Boolean = origin != SkillCatalogOrigin.None

    /** True when a `skills_updated` invalidation arrived after the last enumeration. */
    fun isStale(): Boolean = stale

    fun origin(): SkillCatalogOrigin = origin

    fun snapshot(): JsonArray = skills

    /** Wire shape expected by Iroh admin_rpc skill list clients. */
    fun listEnvelope(): JsonObject =
        buildJsonObject {
            put("skills", snapshot())
            put("hydrated", isHydrated())
            put("stale", isStale())
            put("catalog_source", origin.wireName)
        }

    fun ingest(frame: AppServerInboundFrame) {
        when (frame) {
            is AppServerInboundFrame.UpdateDeviceStatus -> {
                val advertised = extractSkills(frame.deviceStatus["current_available_skills"])
                // A hard-coded empty array is not an enumeration. Only a populated
                // array is evidence that the server knows about any skills at all.
                if (advertised != null && advertised.isNotEmpty()) {
                    hydrate(advertised, SkillCatalogOrigin.DeviceStatus)
                }
            }

            is AppServerInboundFrame.SkillsUpdated -> invalidate()

            is AppServerInboundFrame.SkillEnableResponse -> {
                if (frame.success) applyEnable(frame)
            }

            is AppServerInboundFrame.SkillDisableResponse -> {
                if (frame.success) frame.skillName?.let(::applyDisable)
            }

            is AppServerInboundFrame.Unknown -> {
                // Defensive: an envelope variant we failed to type is still an
                // invalidation signal, never a snapshot source.
                if (frame.type == "skills_updated") invalidate()
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
     * adapter. This is the only supported way to hydrate the catalog when the
     * server itself advertises nothing.
     */
    fun hydrateFromHost(next: JsonArray) = hydrate(
        JsonArray(next.map(::normalizeSkillEntry)),
        SkillCatalogOrigin.HostEnumeration,
    )

    /** Adapt this catalog to the [SkillsListingSource] contract admin handlers consume. */
    fun asListingSource(): SkillsListingSource = object : SkillsListingSource {
        override fun currentSkills(): JsonArray = snapshot()

        override fun isHydrated(): Boolean = this@NativeSkillsCatalog.isHydrated()

        override fun isStale(): Boolean = this@NativeSkillsCatalog.isStale()

        override fun catalogSource(): String = origin().wireName
    }

    private fun hydrate(next: JsonArray, source: SkillCatalogOrigin) {
        synchronized(lock) {
            skills = next
            origin = source
            stale = false
        }
    }

    /** `skills_updated` says "something changed" and nothing more. */
    private fun invalidate() {
        synchronized(lock) {
            if (origin != SkillCatalogOrigin.None) stale = true
        }
    }

    private fun applyEnable(frame: AppServerInboundFrame.SkillEnableResponse) {
        val name = frame.skillName ?: return
        synchronized(lock) {
            // A delta is only meaningful on top of an enumeration; on its own it
            // tells us nothing about the skills we have not observed.
            if (origin == SkillCatalogOrigin.None) return
            val entry = buildJsonObject {
                put("name", name)
                frame.skillPath?.let { put("skill_path", it) }
                frame.linkPath?.let { put("link_path", it) }
            }
            skills = JsonArray(skills.filterNot { nameOf(it) == name } + entry)
            stale = false
        }
    }

    private fun applyDisable(name: String) {
        synchronized(lock) {
            if (origin == SkillCatalogOrigin.None) return
            skills = JsonArray(skills.filterNot { nameOf(it) == name })
            stale = false
        }
    }

    private fun nameOf(element: JsonElement): String? =
        (element as? JsonObject)?.get("name")?.let { (it as? JsonPrimitive)?.contentOrNull }

    private fun extractSkills(element: JsonElement?): JsonArray? {
        if (element == null) return null
        return when (element) {
            is JsonArray -> JsonArray(element.map(::normalizeSkillEntry))
            is JsonObject -> {
                val nested = element["skills"]
                if (nested is JsonArray) JsonArray(nested.map(::normalizeSkillEntry)) else null
            }

            else -> null
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
