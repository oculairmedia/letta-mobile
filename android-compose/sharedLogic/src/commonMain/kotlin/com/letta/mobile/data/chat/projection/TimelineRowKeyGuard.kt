package com.letta.mobile.data.chat.projection

import com.letta.mobile.util.Telemetry

/**
 * The last line before a lazy list: no two rows may ever carry the same key.
 *
 * Compose's LazyColumn throws on a repeated key, which kills the app. The keys come from two
 * sources (the live overlay and the settled pages) whose identities are reconciled upstream, and
 * a defect there (2026-09-24: `segment-ui-msg-9173264` on two settled rows) must cost a missing
 * duplicate row, never a crash.
 */
object TimelineRowKeyGuard {
    /**
     * Indices whose key repeats an earlier row's, each with a key no other row can hold. A null
     * key is a placeholder whose key the list derives from its index, so it never collides.
     * The caller renders these rows as empty; the first occurrence keeps the content.
     */
    fun duplicateRows(keys: List<String?>, sources: (Int) -> String = { "row" }): Map<Int, String> {
        val seen = HashMap<String, Int>(keys.size)
        var duplicates: MutableMap<Int, String>? = null
        keys.forEachIndexed { index, key ->
            if (key == null) return@forEachIndexed
            val first = seen[key]
            if (first == null) {
                seen[key] = index
                return@forEachIndexed
            }
            Telemetry.event(
                "Timeline", "timeline.duplicateKeyDropped",
                "key" to key,
                "sources" to "${sources(first)}+${sources(index)}",
                // The first occurrence keeps the content; this one renders empty.
                "kept" to sources(first),
                "dropped" to sources(index),
                "index" to index,
                level = Telemetry.Level.WARN,
            )
            val map = duplicates ?: mutableMapOf<Int, String>().also { duplicates = it }
            map[index] = "$key#duplicate-$index"
        }
        return duplicates.orEmpty()
    }
}
