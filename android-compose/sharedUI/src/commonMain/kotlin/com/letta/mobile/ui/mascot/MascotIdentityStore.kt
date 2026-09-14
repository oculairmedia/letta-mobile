package com.letta.mobile.ui.mascot

import com.letta.mobile.avatar.core.MascotIdentity
import com.letta.mobile.data.model.Agent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Where an agent's chosen [MascotIdentity] lives. The identity is part of the agent, not of the
 * device: it is written into the agent's metadata under [MASCOT_IDENTITY_METADATA_KEY] (encoded
 * with `MascotIdentity.encode`), so a choice made on one client is the choice every other client
 * shows. The per-device setting keyed by [mascotIdentitySettingsKey] is a cache and the pre-metadata
 * legacy store: read when the agent carries no identity, written alongside so an offline client
 * still shows the last choice.
 */
const val MASCOT_IDENTITY_METADATA_KEY: String = "letta_mobile.avatar_style"

/** The device-local settings key for [agentId]'s identity (cache / legacy). */
fun mascotIdentitySettingsKey(agentId: String): String = "agent.$agentId.avatar_style"

/** The identity stored on the agent, or null when it carries none. */
fun Agent.mascotIdentity(): MascotIdentity? =
    MascotIdentity.decode(metadata[MASCOT_IDENTITY_METADATA_KEY]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() })

/** The agent's identity: the agent's own first, then the device's cached / legacy [localValue]. */
fun resolveMascotIdentity(agent: Agent?, localValue: String?): MascotIdentity? =
    agent?.mascotIdentity() ?: MascotIdentity.decode(localValue)

/** [existing] metadata with this identity written in - the whole map, since an agent PATCH replaces it. */
fun MascotIdentity.withinAgentMetadata(existing: Map<String, JsonElement>?): Map<String, JsonElement> =
    existing.orEmpty() + (MASCOT_IDENTITY_METADATA_KEY to JsonPrimitive(encode()))
