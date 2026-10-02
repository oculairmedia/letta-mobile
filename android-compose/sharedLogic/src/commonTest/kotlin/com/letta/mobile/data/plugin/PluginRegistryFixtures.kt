package com.letta.mobile.data.plugin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.assertIs

/** Manifests, registries and JSON edits for the plugin contract tests (letta-mobile-s416w.24). */
internal object PluginRegistryFixtures {
    /** The plan's example manifest (section 3.1), the same document as `manifest-example.json`. */
    const val EXAMPLE: String = """
    {
      "manifestVersion": 1, "id": "letta.example", "name": "Example", "version": "1.2.0", "publisher": "oculairmedia",
      "contract": { "version": 1 },
      "runtime": { "kind": "jvm", "jar": "plugin.jar", "entry": "com.example.ExamplePlugin" },
      "settings": {
        "baseUrl": { "type": "string", "format": "uri", "required": true, "label": "Service URL" },
        "quality": { "type": "integer", "minimum": 1, "maximum": 100, "default": 80 }
      },
      "secrets": [ { "name": "apiToken", "label": "API token", "required": false } ],
      "capabilities": ["canvas:place", "canvas:read", "assets:write", "net:connect", "ui:pages"],
      "net": { "connect": ["https://api.example.test", "ws://127.0.0.1:8188"] },
      "elements": {
        "widget": {
          "schemaVersion": 2,
          "props": { "type": "object", "additionalProperties": false, "properties": {
            "status": { "enum": ["idle", "running", "done", "failed"] },
            "progress": { "type": "number", "minimum": 0, "maximum": 1 },
            "label": { "type": "string", "maxLength": 128 } } },
          "defaultSize": { "width": 320, "height": 240 },
          "page": "widget",
          "migrations": { "1": [ { "rename": ["pct", "progress"] }, { "default": ["status", "idle"] } ] }
        }
      },
      "actions": {
        "start": { "description": "Start a job and place a widget", "visibility": ["agent", "view"],
                   "input": { "type": "object", "additionalProperties": false, "required": ["label"],
                              "properties": { "label": { "type": "string", "maxLength": 128 } } } },
        "cancel": { "description": "Cancel a job", "visibility": ["agent", "view"],
                    "input": { "type": "object", "additionalProperties": false, "properties": { "elementId": { "type": "string" } } } },
        "refresh": { "visibility": ["view"], "input": { "type": "object", "additionalProperties": false, "properties": {} } }
      },
      "pages": {
        "widget": { "html": "pages/widget.html",
                    "csp": { "connectDomains": [], "resourceDomains": [], "frameDomains": [] },
                    "permissions": [], "displayModes": ["inline", "fullscreen"] }
      }
    }
    """

    const val SHA_A: String = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    const val SHA_B: String = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    const val SHA_C: String = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"

    val exampleDocument: JsonElement get() = Json.parseToJsonElement(EXAMPLE)

    /** The example manifest with [edits] (pointer to value; null removes) applied, parsed or failing the test. */
    fun manifest(vararg edits: Pair<String, JsonElement?>): PluginManifest {
        val document = edits.fold(exampleDocument) { doc, (path, value) -> if (value == null) JsonPatch.remove(doc, path) else JsonPatch.set(doc, path, value) }
        return assertIs<PluginManifestResult.Parsed>(PluginManifestParser.parse(document)).manifest
    }

    val example: PluginManifest get() = manifest()

    /** [manifest] at another [version]. */
    fun version(version: String, vararg edits: Pair<String, JsonElement?>): PluginManifest =
        manifest("/version" to JsonPrimitive(version), *edits)

    /** A registry holding [manifests], each installed with full consent and nothing missing. */
    fun registryOf(vararg manifests: PluginManifest): PluginRegistryState = manifests.fold(PluginRegistryState()) { state, manifest ->
        val install = PluginCommand.Install(manifest, SHA_A, PluginConsent.forManifest(manifest, NOW), NOW, PluginSettingsSummary(setOf("baseUrl")))
        assertIs<PluginTransition.Applied>(PluginRegistryReducer.reduce(state, install)).state
    }

    const val NOW: Long = 1_700_000_000_000L

    fun json(text: String): JsonElement = Json.parseToJsonElement(text)
}

/** Sets and removes values at JSON pointers; an array index one past the end appends. */
internal object JsonPatch {
    fun set(document: JsonElement, path: String, value: JsonElement): JsonElement = edit(document, segments(path)) { value }

    fun remove(document: JsonElement, path: String): JsonElement = edit(document, segments(path)) { null }

    private fun segments(path: String): List<String> = path.removePrefix("/").split('/').map { it.replace("~1", "/").replace("~0", "~") }

    private fun edit(node: JsonElement, path: List<String>, change: (JsonElement?) -> JsonElement?): JsonElement {
        val key = path.first()
        val rest = path.drop(1)
        return when (node) {
            is JsonObject -> JsonObject(node.toMutableMap().also { fields -> update(fields, key, rest, change) })
            is JsonArray -> JsonArray(node.toMutableList().also { items -> update(items, key.toInt(), rest, change) })
            else -> error("cannot descend into $node at $key")
        }
    }

    private fun update(fields: MutableMap<String, JsonElement>, key: String, rest: List<String>, change: (JsonElement?) -> JsonElement?) {
        val next = if (rest.isEmpty()) change(fields[key]) else edit(fields.getValue(key), rest, change)
        if (next == null) fields.remove(key) else fields[key] = next
    }

    private fun update(items: MutableList<JsonElement>, index: Int, rest: List<String>, change: (JsonElement?) -> JsonElement?) {
        val current = items.getOrNull(index)
        val next = if (rest.isEmpty()) change(current) else edit(checkNotNull(current), rest, change)
        when {
            next == null -> items.removeAt(index)
            index == items.size -> items.add(next)
            else -> items[index] = next
        }
    }
}
