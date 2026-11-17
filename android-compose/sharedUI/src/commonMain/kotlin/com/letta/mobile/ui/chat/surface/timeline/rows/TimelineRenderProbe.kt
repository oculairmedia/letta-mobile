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
    private val keys = linkedSetOf<String>()
    fun mount(key: String) { keys.add(key) }
    fun dispose(key: String) { keys.remove(key) }
    fun isHeartbeatOwner(key: String): Boolean = keys.firstOrNull() == key
}
