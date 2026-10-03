package com.letta.mobile.data.plugin

import com.letta.mobile.data.schema.JsonSchemaCheck
import com.letta.mobile.data.schema.SchemaProblem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The shape of `letta-plugin.json` in the shared [JsonSchemaCheck] vocabulary (plan section 3.1):
 * every object closed, every map's keys patterned, capabilities a closed enum, `runtime` told apart
 * by `kind`. What a schema cannot say (references between parts, origins, paths, templates) is
 * [PluginManifestRules].
 */
object PluginManifestSchema {
    const val ID_PATTERN: String = "^[a-z0-9]+(\\.[a-z0-9]+)+$"
    const val NAME_PATTERN: String = "^[a-zA-Z][a-zA-Z0-9_]{0,63}$"
    const val ACTION_PATTERN: String = "^[a-z][a-z0-9_]{0,47}$"
    const val KIND_PATTERN: String = "^[a-z0-9-]{1,32}$"

    /** Semantic versioning 2.0.0: `1.2.0`, `1.2.0-beta.1`, `1.2.0+build.5`. */
    const val SEMVER_PATTERN: String =
        "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?(\\+[0-9A-Za-z-]+(\\.[0-9A-Za-z-]+)*)?$"

    /** Every place [manifest] breaks the schema, at its JSON pointer; empty when it holds. */
    fun check(manifest: JsonElement): List<SchemaProblem> = checker.check(manifest)

    private fun strings(maxItems: Int, maxLength: Int = 256) =
        """{"type":"array","maxItems":$maxItems,"items":{"type":"string","minLength":1,"maxLength":$maxLength}}"""

    private fun enumArray(values: List<String>, maxItems: Int) =
        """{"type":"array","maxItems":$maxItems,"items":{"enum":[${values.joinToString { "\"$it\"" }}]}}"""

    private fun map(keyPattern: String, maxProperties: Int, values: String) =
        """{"type":"object","maxProperties":$maxProperties,"propertyNames":{"type":"string","pattern":"${keyPattern.replace("\\", "\\\\")}"},"additionalProperties":$values}"""

    private val text = """{"type":"string","minLength":1,"maxLength":64}"""

    private val jvm = """{"type":"object","additionalProperties":false,"required":["kind","jar","entry"],"properties":{
        "kind":{"enum":["jvm"]},
        "jar":{"type":"string","minLength":1,"maxLength":256},
        "entry":{"type":"string","maxLength":256,"pattern":"^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*$"}}}"""

    private val process = """{"type":"object","additionalProperties":false,"required":["kind","command"],"properties":{
        "kind":{"enum":["process"]},
        "command":{"type":"string","minLength":1,"maxLength":256},
        "args":${strings(64, 1024)},
        "cwd":{"type":"string","minLength":1,"maxLength":256},
        "env":${map("^[A-Z_][A-Z0-9_]{0,63}$", 64, """{"type":"string","maxLength":4096}""")}}}"""

    private val service = """{"type":"object","additionalProperties":false,"required":["kind","url"],"properties":{
        "kind":{"enum":["service"]},
        "url":{"type":"string","maxLength":1024,"pattern":"^wss?://[^\\s]+$"},
        "headers":${map("^[A-Za-z0-9-]{1,64}$", 32, """{"type":"string","maxLength":4096}""")}}}"""

    private val setting = """{"type":"object","additionalProperties":false,"required":["type"],"properties":{
        "type":{"enum":["string","integer","number","boolean","enum"]},
        "label":$text,
        "description":{"type":"string","maxLength":512},
        "required":{"type":"boolean"},
        "default":{},
        "format":{"enum":["uri"]},
        "minimum":{"type":"number"},
        "maximum":{"type":"number"},
        "maxLength":{"type":"integer","minimum":1,"maximum":4096},
        "values":{"type":"array","minItems":1,"maxItems":64,"items":{"type":"string","minLength":1,"maxLength":128}}}}"""

    private val secret = """{"type":"object","additionalProperties":false,"required":["name"],"properties":{
        "name":{"type":"string","pattern":"${NAME_PATTERN.replace("\\", "\\\\")}"},
        "label":$text,
        "required":{"type":"boolean"}}}"""

    private val migrationStep = """{"type":"object","additionalProperties":false,"properties":{
        "rename":{"type":"array","minItems":2,"maxItems":2,"items":{"type":"string","minLength":1,"maxLength":64}},
        "default":{"type":"array","minItems":2,"maxItems":2},
        "drop":{"type":"string","minLength":1,"maxLength":64}}}"""

    private val element = """{"type":"object","additionalProperties":false,"required":["schemaVersion","props"],"properties":{
        "schemaVersion":{"type":"integer","minimum":1,"maximum":9999},
        "props":{"type":"object"},
        "defaultSize":{"type":"object","additionalProperties":false,"required":["width","height"],"properties":{
            "width":{"type":"number","minimum":1,"maximum":100000},
            "height":{"type":"number","minimum":1,"maximum":100000}}},
        "page":{"type":"string","pattern":"${KIND_PATTERN.replace("\\", "\\\\")}"},
        "migrations":${map("^[1-9][0-9]{0,3}$", 64, """{"type":"array","maxItems":32,"items":$migrationStep}""")}}}"""

    private val action = """{"type":"object","additionalProperties":false,"required":["visibility","input"],"properties":{
        "description":{"type":"string","minLength":1,"maxLength":1024},
        "visibility":{"type":"array","minItems":1,"maxItems":2,"items":{"enum":["agent","view"]}},
        "input":{"type":"object"}}}"""

    private val page = """{"type":"object","additionalProperties":false,"required":["html"],"properties":{
        "html":{"type":"string","minLength":1,"maxLength":256},
        "csp":{"type":"object","additionalProperties":false,"properties":{
            "connectDomains":${strings(32)},"resourceDomains":${strings(32)},"frameDomains":${strings(32)}}},
        "permissions":${enumArray(PluginCapability.pagePermissions.map { it.wire }, 8)},
        "displayModes":{"type":"array","minItems":1,"maxItems":3,"items":{"enum":["inline","fullscreen","pip"]}}}}"""

    private val manifest = """{"type":"object","additionalProperties":false,
        "required":["manifestVersion","id","name","version","publisher","contract","runtime"],"properties":{
        "manifestVersion":{"enum":[1]},
        "id":{"type":"string","maxLength":64,"pattern":"${ID_PATTERN.replace("\\", "\\\\")}"},
        "name":$text,
        "version":{"type":"string","maxLength":64,"pattern":"${SEMVER_PATTERN.replace("\\", "\\\\")}"},
        "publisher":$text,
        "contract":{"type":"object","additionalProperties":false,"required":["version"],"properties":{
            "version":{"type":"integer","minimum":1}}},
        "runtime":{"anyOf":[$jvm,$process,$service]},
        "settings":${map(NAME_PATTERN, 64, setting)},
        "secrets":{"type":"array","maxItems":32,"items":$secret},
        "capabilities":${enumArray(PluginCapability.wireNames, 16)},
        "net":{"type":"object","additionalProperties":false,"properties":{"connect":${strings(32)}}},
        "elements":${map(KIND_PATTERN, 32, element)},
        "actions":${map(ACTION_PATTERN, 64, action)},
        "pages":${map(KIND_PATTERN, 16, page)}}}"""

    /** The whole schema, as a document (for docs and for clients that check before uploading). */
    val schema: JsonObject by lazy { Json.parseToJsonElement(manifest).jsonObject }

    private val checker by lazy { JsonSchemaCheck(schema, discriminator = "kind") }
}
