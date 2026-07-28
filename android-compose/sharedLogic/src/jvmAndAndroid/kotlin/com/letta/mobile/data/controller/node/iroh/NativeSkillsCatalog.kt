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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Controller-side projection of available skills.
 *
 * Upstream has no `skill_list` command; listings hydrate from
 * `update_device_status.device_status.current_available_skills` and
 * `skills_updated` events (see ownership matrix / protocol inventory).
 */
class NativeSkillsCatalog {
    @Volatile
    private var skills: JsonArray = JsonArray(emptyList())

    @Volatile
    private var hydrated: Boolean = false

    fun isHydrated(): Boolean = hydrated

    fun snapshot(): JsonArray = skills

    /** Wire shape expected by Iroh admin_rpc skill list clients. */
    fun listEnvelope(): JsonObject =
        buildJsonObject { put("skills", snapshot()) }

    fun ingest(frame: AppServerInboundFrame) {
        when (frame) {
            is AppServerInboundFrame.UpdateDeviceStatus -> {
                extractSkills(frame.deviceStatus["current_available_skills"])?.let(::replace)
            }
            is AppServerInboundFrame.SkillsUpdated -> {
                extractSkills(frame.skills)?.let(::replace)
            }
            is AppServerInboundFrame.Unknown -> {
                if (frame.type == "skills_updated") {
                    extractSkills(frame.raw["skills"])?.let(::replace)
                }
            }
            else -> Unit
        }
    }

    fun ingestReceived(received: AppServerReceivedFrame) = ingest(received.frame)

    fun start(scope: CoroutineScope, events: Flow<AppServerReceivedFrame>): Job =
        scope.launch {
            events.collect { ingestReceived(it) }
        }

    /** Test / bootstrap hook. */
    fun replace(next: JsonArray) {
        skills = next
        hydrated = true
    }

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

/** Optional injectable listing source for [SkillAdminHandlers]. */
fun interface SkillsListingSource {
    fun currentSkills(): JsonArray?
}
