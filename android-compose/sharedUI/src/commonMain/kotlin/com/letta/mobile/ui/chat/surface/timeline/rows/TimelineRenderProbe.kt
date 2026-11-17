package com.letta.mobile.ui.chat.surface.timeline.rows

/** Receives root-debug row evidence without depending on host telemetry. */
fun interface TimelineRenderSink {
    /** Reports a row event; draw events include identity and content hashes. */
    fun record(name: String, key: String, identity: String?, stored: Boolean, assistant: Boolean, hashes: String?)
}

/** Optional root-debug evidence sink, installed before timeline composition. */
object TimelineRenderProbe {
    /** Null disables diagnostic modifiers and lifecycle effects. */
    var sink: TimelineRenderSink? = null
    internal val residents = TimelineProbeResidents()
}

internal class TimelineProbeResidents {
    private val copies = linkedMapOf<String, Int>()
    private val assistants = linkedSetOf<String>()

    fun mount(key: String, assistant: Boolean = true): Boolean {
        val count = (copies[key] ?: 0) + 1
        copies[key] = count
        if (assistant) assistants.add(key)
        return count == 1
    }

    fun dispose(key: String): Boolean {
        val count = copies[key] ?: return false
        if (count > 1) {
            copies[key] = count - 1
            return false
        }
        copies.remove(key)
        assistants.remove(key)
        return true
    }

    fun isHeartbeatOwner(key: String): Boolean = assistants.firstOrNull() == key
}
