package com.letta.mobile.data.plugin.view

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * `lcp-view/1` (plan section 7.2, letta-mobile-s416w.31): the view bridge between a plugin page and
 * its host. JSON-RPC 2.0 over postMessage with a fixed method set; nothing outside it is answered.
 */
object LcpView {
    const val PROTOCOL: String = "lcp-view/1"

    /** The `viewVersion`s a page (its shim) may announce in `view.ready`. */
    val SUPPORTED_VIEW_VERSIONS: Set<String> = setOf("1")

    /** The largest message either side may send, UTF-8 encoded. */
    const val MAX_MESSAGE_BYTES: Int = 256 * 1024

    /** How many messages a view may send per second before the bridge refuses them. */
    const val MAX_MESSAGES_PER_SECOND: Int = 20

    /** How long the host waits for the page to acknowledge `host.teardown`. */
    const val TEARDOWN_TIMEOUT_MS: Long = 2_000

    /** The longest request id a view may use when it is a string. */
    const val MAX_ID_LENGTH: Int = 64
}

/** The host→view methods: `host.context` and `host.element.changed` are notifications, `host.teardown` a request. */
object HostMethod {
    const val CONTEXT: String = "host.context"
    const val ELEMENT_CHANGED: String = "host.element.changed"
    const val TEARDOWN: String = "host.teardown"
}

/** How a page logs through the host (`view.log`). */
enum class ViewLogLevel(val wire: String) {
    DEBUG("debug"),
    INFO("info"),
    WARN("warn"),
    ERROR("error"),
    ;

    companion object {
        fun of(wire: String): ViewLogLevel? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The view→host method set. A [notification] carries no id and gets no answer; every other method
 * is a request and must carry one. [params] is the closed schema its params are held to.
 */
enum class ViewMethod(val wire: String, val notification: Boolean, val params: JsonObject) {
    READY("view.ready", false, closed(required = listOf("pageId", "viewVersion")) {
        putJsonObject("pageId") { string(maxLength = 128) }
        putJsonObject("viewVersion") { string(maxLength = 32) }
    }),
    ACTION("view.action", false, closed(required = listOf("action")) {
        putJsonObject("action") { string(maxLength = 64) }
        putJsonObject("input") { put("type", "object") }
    }),
    RESIZE("view.resize", true, closed(required = listOf("width", "height")) {
        putJsonObject("width") { size() }
        putJsonObject("height") { size() }
    }),
    DISPLAY_MODE("view.displayMode", false, closed(required = listOf("mode")) {
        putJsonObject("mode") { put("enum", JsonArray(listOf("inline", "fullscreen", "pip").map(::JsonPrimitive))) }
    }),
    OPEN_LINK("view.openLink", false, closed(required = listOf("url")) {
        putJsonObject("url") { string(maxLength = 2048) }
    }),
    LOG("view.log", true, closed(required = listOf("level", "message")) {
        putJsonObject("level") { put("enum", JsonArray(ViewLogLevel.entries.map { JsonPrimitive(it.wire) })) }
        putJsonObject("message") { string(maxLength = 2000, minLength = 0) }
    }),
    ;

    companion object {
        /** The largest frame a view may ask for, in either dimension. */
        const val MAX_VIEW_SIZE: Int = 10_000

        fun of(wire: String): ViewMethod? = entries.firstOrNull { it.wire == wire }
    }
}

private fun closed(required: List<String>, properties: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
    put("type", "object")
    put("additionalProperties", false)
    put("required", JsonArray(required.map(::JsonPrimitive)))
    putJsonObject("properties", properties)
}

private fun JsonObjectBuilder.string(maxLength: Int, minLength: Int = 1) {
    put("type", "string")
    put("minLength", minLength)
    put("maxLength", maxLength)
}

private fun JsonObjectBuilder.size() {
    put("type", "number")
    put("minimum", 1)
    put("maximum", ViewMethod.MAX_VIEW_SIZE)
}
