package com.letta.mobile.data.plugin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * A plugin's `letta-plugin.json` (plan section 3.1, letta-mobile-s416w.24): the contract between a
 * package and the host. Only [PluginManifestParser] builds one, after [PluginManifestSchema] and
 * [PluginManifestRules] have held the document, so every instance is valid.
 *
 * Maps keep the manifest's key order (kinds, actions, pages and settings are shown in that order).
 */
@Serializable
data class PluginManifest(
    val manifestVersion: Int,
    val id: String,
    val name: String,
    val version: String,
    val publisher: String,
    val contract: PluginContract,
    val runtime: PluginRuntime,
    val settings: Map<String, PluginSettingField> = emptyMap(),
    val secrets: List<PluginSecretDecl> = emptyList(),
    val capabilities: List<PluginCapability> = emptyList(),
    val net: PluginNet = PluginNet(),
    val elements: Map<String, PluginElementKind> = emptyMap(),
    val actions: Map<String, PluginAction> = emptyMap(),
    val pages: Map<String, PluginPage> = emptyMap(),
) {
    /** The last segment of [id], the prefix of every agent tool the plugin offers: `example` of `letta.example`. */
    val idShort: String get() = id.substringAfterLast('.')

    /** The element type of [kind]: `ext:letta.example/widget`. */
    fun elementType(kind: String): String = "ext:$id/$kind"

    /** The agent tool name of [action]: `example_start`. */
    fun toolName(action: String): String = "${idShort}_$action"
}

@Serializable
data class PluginContract(val version: Int)

/** Where the plugin's code runs (plan section 1): in the host, as a subprocess, or as a service. */
@Serializable
sealed interface PluginRuntime {
    /** A jar implementing the Kotlin SPI, loaded by the host in an isolated class loader (R1: consented code). */
    @Serializable
    @SerialName("jvm")
    data class Jvm(val jar: String, val entry: String) : PluginRuntime

    /** Any language speaking LCP wire v1 over NDJSON stdio; [env] values may hold `${settings.*}`/`${secrets.*}`. */
    @Serializable
    @SerialName("process")
    data class Process(
        val command: String,
        val args: List<String> = emptyList(),
        val cwd: String = ".",
        val env: Map<String, String> = emptyMap(),
    ) : PluginRuntime

    /** A WebSocket service speaking LCP wire v1; [headers] may hold templates, [url] never a secret. */
    @Serializable
    @SerialName("service")
    data class Service(val url: String, val headers: Map<String, String> = emptyMap()) : PluginRuntime

    companion object {
        /** The manifest's `runtime.kind` of [runtime]. */
        fun kindOf(runtime: PluginRuntime): String = when (runtime) {
            is Jvm -> "jvm"
            is Process -> "process"
            is Service -> "service"
        }
    }
}

/** The value types a setting can have (plan section 7.1: what the settings form can render). */
@Serializable
enum class PluginSettingType {
    @SerialName("string") STRING,
    @SerialName("integer") INTEGER,
    @SerialName("number") NUMBER,
    @SerialName("boolean") BOOLEAN,
    @SerialName("enum") ENUM,
}

/** One typed setting the user fills in from the settings UI. */
@Serializable
data class PluginSettingField(
    val type: PluginSettingType,
    val label: String? = null,
    val description: String? = null,
    val required: Boolean = false,
    val default: JsonElement? = null,
    val format: String? = null,
    val minimum: Double? = null,
    val maximum: Double? = null,
    val maxLength: Int? = null,
    val values: List<String>? = null,
)

/** A secret the plugin needs: write-only from the UI, delivered by the host only. */
@Serializable
data class PluginSecretDecl(val name: String, val label: String? = null, val required: Boolean = false)

@Serializable
data class PluginNet(val connect: List<String> = emptyList())

/** One element kind the plugin places on boards, at its current [schemaVersion]. */
@Serializable
data class PluginElementKind(
    val schemaVersion: Int,
    val props: JsonObject,
    val defaultSize: PluginElementSize? = null,
    val page: String? = null,
    val migrations: Map<String, List<PluginMigrationStep>> = emptyMap(),
)

@Serializable
data class PluginElementSize(val width: Float, val height: Float)

/**
 * One declarative step of a props migration, as written: exactly one of [rename] (`[from, to]`),
 * [default] (`[field, value]`) or [drop] (a field). [PluginMigrations] reads it through [op].
 */
@Serializable
data class PluginMigrationStep(
    val rename: List<String>? = null,
    val default: JsonArray? = null,
    val drop: String? = null,
)

/** Who may invoke an action: the agent (it becomes a tool), the plugin's own pages, or both. */
@Serializable
enum class PluginActionVisibility {
    @SerialName("agent") AGENT,
    @SerialName("view") VIEW,
}

@Serializable
data class PluginAction(
    val description: String? = null,
    val visibility: List<PluginActionVisibility>,
    val input: JsonObject,
)

@Serializable
data class PluginPage(
    val html: String,
    val csp: PluginPageCsp = PluginPageCsp(),
    val permissions: List<PluginCapability> = emptyList(),
    val displayModes: List<PluginDisplayMode> = listOf(PluginDisplayMode.INLINE),
)

@Serializable
data class PluginPageCsp(
    val connectDomains: List<String> = emptyList(),
    val resourceDomains: List<String> = emptyList(),
    val frameDomains: List<String> = emptyList(),
)

@Serializable
enum class PluginDisplayMode {
    @SerialName("inline") INLINE,
    @SerialName("fullscreen") FULLSCREEN,
    @SerialName("pip") PIP,
}
