package com.letta.mobile.data.plugin.view

import com.letta.mobile.data.canvas.CanvasDocumentFrame
import com.letta.mobile.data.canvas.plugin.CanvasPluginElement
import com.letta.mobile.data.canvas.plugin.CanvasPluginFallback
import com.letta.mobile.data.canvas.plugin.CanvasPluginSnapshot
import com.letta.mobile.data.plugin.PluginDisplayMode
import com.letta.mobile.data.plugin.PluginManifest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
enum class ViewTheme {
    @SerialName("light") LIGHT,
    @SerialName("dark") DARK,
}

@Serializable
enum class ViewPlatform {
    @SerialName("android") ANDROID,
    @SerialName("desktop") DESKTOP,
    @SerialName("web") WEB,
}

/** Insets the page should keep clear, in CSS pixels. */
@Serializable
data class ViewSafeArea(val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f, val left: Float = 0f)

/**
 * The element a view shows, as the page sees it: the board entry without the host's provenance
 * ([CanvasPluginElement.meta]), the owner or the plugin's opaque ref.
 */
@Serializable
data class ViewElement(
    val id: String,
    val type: String,
    val v: Int,
    val frame: CanvasDocumentFrame? = null,
    val props: JsonObject = JsonObject(emptyMap()),
    val snapshot: CanvasPluginSnapshot? = null,
    val fallback: CanvasPluginFallback,
) {
    companion object {
        fun of(element: CanvasPluginElement): ViewElement = ViewElement(
            id = element.id,
            type = element.type,
            v = element.v,
            frame = element.frame,
            props = element.props,
            snapshot = element.snapshot,
            fallback = element.fallback,
        )
    }
}

/**
 * What `host.context` tells a page (plan section 7.2): its look ([theme], [cssVariables] of the
 * Material tokens), the [element] it shows, the plugin's public settings, how it is shown, and where.
 */
@Serializable
data class ViewHostContext(
    val theme: ViewTheme,
    val cssVariables: Map<String, String>,
    val element: ViewElement,
    val settingsPublic: JsonObject,
    val displayMode: PluginDisplayMode,
    val locale: String,
    val platform: ViewPlatform,
    val safeArea: ViewSafeArea = ViewSafeArea(),
) {
    fun toJson(): JsonObject = json.encodeToJsonElement(serializer(), this).jsonObject

    companion object {
        private val json = Json { encodeDefaults = true; explicitNulls = false }

        /** [element] as a page sees it. */
        fun elementJson(element: ViewElement): JsonObject = json.encodeToJsonElement(ViewElement.serializer(), element).jsonObject

        /**
         * The settings a page may see: only fields [manifest] declares under `settings`. Secrets are
         * a separate list the host never hands to a page, so nothing else in [values] passes.
         */
        fun publicSettings(manifest: PluginManifest, values: JsonObject): JsonObject {
            val visible = manifest.settings.keys - manifest.secrets.map { it.name }.toSet()
            return JsonObject(values.filterKeys { it in visible })
        }
    }
}
