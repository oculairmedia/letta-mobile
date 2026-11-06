package com.letta.mobile.data.pins

import com.letta.mobile.data.home.shortcut.HomeShortcut
import com.letta.mobile.data.objectverbs.ObjectRef
import com.letta.mobile.data.serialization.LettaJson
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.data.storage.SettingsPreferencesSnapshot
import kotlinx.serialization.encodeToString

/** The complete inventory, in the accepted precedence order. Unavailable backings stay uncompleted. */
object LegacyPinSources {
    const val SETTINGS_ORDER = "pinned_items_order"
    const val HOME_PRODUCTION = "desktop.home.pinnedItems"
    const val HOME_DEFAULT = "home.pinnedItems"
    const val SETTINGS_CONVERSATIONS = "pinned_conversation_ids"
    const val SETTINGS_PROJECTS = "pinned_project_ids"
    val keys: List<String> = listOf(
        SETTINGS_ORDER, HOME_PRODUCTION, HOME_DEFAULT, SETTINGS_CONVERSATIONS, SETTINGS_PROJECTS,
        "desktop.conversations.pinned", "conversations.pinned", "projects.pinned", "desktop.projects.pinned",
    )

    /** Only the supplied secure backings are readable here. Android must never checkpoint desktop keys. */
    fun secure(store: SecureSettingsStore, available: Set<String>): List<LegacyPinSource> = keys.filter { it in available }.map { key ->
        LegacyPinSource(key, { store.getString(key) }) { raw ->
            raw.split('\n').map(String::trim).filter(String::isNotEmpty).let { entries ->
                when (key) {
                    "desktop.conversations.pinned", "conversations.pinned" -> entries.sorted().map { "conversation:$it" }
                    "projects.pinned", "desktop.projects.pinned" -> entries.sorted().map { "project:$it" }
                    else -> entries
                }
            }
        }
    }

    /** Reads the actual settings snapshot, including corrupt JSON and explicit empty sets. */
    fun settings(snapshot: SettingsPreferencesSnapshot): List<LegacyPinSource> = listOf(
        LegacyPinSource(SETTINGS_ORDER, { snapshot.getString(SETTINGS_ORDER) }) { LettaJson.wire.decodeFromString<List<String>>(it) },
        membership(snapshot, SETTINGS_CONVERSATIONS, "conversation"),
        membership(snapshot, SETTINGS_PROJECTS, "project"),
    )

    private fun membership(snapshot: SettingsPreferencesSnapshot, key: String, kind: String) = LegacyPinSource(
        key, { snapshot.getStringSet(key)?.let { LettaJson.wire.encodeToString(it.sorted()) } },
    ) { raw -> LettaJson.wire.decodeFromString<List<String>>(raw).sorted().map { "$kind:$it" } }

    /** Unknown kinds/names and bare default conversations remain opaque, with no guessed backend. */
    fun objectRef(entry: String): ObjectRef? {
        val kind = entry.substringBefore(':', "")
        val id = entry.substringAfter(':', "")
        return ObjectRef(kind, id).takeIf {
            id.isNotBlank() && when (kind) {
                "shortcut" -> HomeShortcut.entries.any { it.name == id }
                "conversation" -> id != com.letta.mobile.data.model.IdFormats.DEFAULT_CONVERSATION_ID
                "agent", "project", "canvas" -> true
                else -> false
            }
        }
    }

    /** Shortcut identity is device-local; domain ownership must come from trusted evidence. */
    fun shortcut(entry: String): ScopedObjectRef? = objectRef(entry)?.takeIf { it.kind == "shortcut" }
        ?.let { ScopedObjectRef(ScopedObjectRef.DEVICE_SOURCE, it) }
}
