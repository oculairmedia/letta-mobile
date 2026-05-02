@file:Suppress("CyclomaticComplexMethod", "MaxLineLength", "ReturnCount", "TooGenericExceptionCaught", "TooManyFunctions")

package com.letta.mobile.bot.tools

import com.letta.mobile.bot.context.DeviceContextProvider
import com.letta.mobile.data.model.ToolCreateParams
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

private const val SCHEMA_TYPE_OBJECT = "object"
private const val SCHEMA_TYPE_STRING = "string"
private const val SCHEMA_TYPE_BOOLEAN = "boolean"

data class BotToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true,
)

data class BotToolDefinition(
    val name: String,
    val description: String,
    val tags: Set<String> = emptySet(),
    val parameters: List<BotToolParameter> = emptyList(),
)

sealed interface BotToolExecutionResult {
    val toolName: String

    data class Success(
        override val toolName: String,
        val payload: String,
    ) : BotToolExecutionResult

    data class Unavailable(
        override val toolName: String,
        val reason: String,
    ) : BotToolExecutionResult

    data class Failure(
        override val toolName: String,
        val error: String,
    ) : BotToolExecutionResult
}

@Singleton
class BotToolRegistry @Inject constructor(
    private val contextProviders: @JvmSuppressWildcards Set<DeviceContextProvider>,
    private val androidExecutionBridge: AndroidExecutionBridge,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private val definitions = mapOf(
        "get_battery_status" to BotToolDefinition(
            name = "get_battery_status",
            description = "Get current Android battery status and charging state",
            tags = setOf("device", "battery", "context"),
        ),
        "get_connectivity_status" to BotToolDefinition(
            name = "get_connectivity_status",
            description = "Get current Android network connectivity status",
            tags = setOf("device", "network", "context"),
        ),
        "get_current_time" to BotToolDefinition(
            name = "get_current_time",
            description = "Get current local date, time, and timezone on the Android device",
            tags = setOf("device", "time", "context"),
        ),
        "get_device_context" to BotToolDefinition(
            name = "get_device_context",
            description = "Get a combined snapshot of battery, network, and time context from the Android device",
            tags = setOf("device", "context"),
        ),
        "launch_main_app" to BotToolDefinition(
            name = "launch_main_app",
            description = "Bring the Letta Mobile app to the foreground",
            tags = setOf("android", "app", "launch"),
        ),
        "launch_app" to BotToolDefinition(
            name = "launch_app",
            description = "Launch an installed Android app by package name",
            tags = setOf("android", "app", "launch"),
            parameters = listOf(
                BotToolParameter("package_name", SCHEMA_TYPE_STRING, "Android package name to launch, for example com.android.settings."),
            ),
        ),
        "write_clipboard" to BotToolDefinition(
            name = "write_clipboard",
            description = "Write text to the Android clipboard",
            tags = setOf("android", "clipboard"),
            parameters = listOf(BotToolParameter("text", SCHEMA_TYPE_STRING, "Text to place on the Android clipboard.")),
        ),
        "read_clipboard" to BotToolDefinition(
            name = "read_clipboard",
            description = "Read the current Android clipboard text",
            tags = setOf("android", "clipboard"),
        ),
        "notification_status" to BotToolDefinition(
            name = "notification_status",
            description = "Get notification permission and active notification status for the Android device",
            tags = setOf("android", "notifications"),
        ),
        "list_launchable_apps" to BotToolDefinition(
            name = "list_launchable_apps",
            description = "List launchable apps installed on the Android device",
            tags = setOf("android", "apps"),
        ),
        "android_open_wifi_settings" to BotToolDefinition(
            name = "android_open_wifi_settings",
            description = "Open Android Wi-Fi settings. This only opens Settings UI; it does not silently change Wi-Fi state.",
            tags = setOf("android", "settings", "wifi", "host-tool"),
        ),
        "android_show_location_on_map" to BotToolDefinition(
            name = "android_show_location_on_map",
            description = "Open a user-selected map app for a location query, place name, business, or address.",
            tags = setOf("android", "maps", "location", "host-tool"),
            parameters = listOf(
                BotToolParameter("location", SCHEMA_TYPE_STRING, "Location query, place name, business, or address to show on a map."),
            ),
        ),
        "android_send_email_draft" to BotToolDefinition(
            name = "android_send_email_draft",
            description = "Open an email app with a draft addressed to the recipient. This does not send the email.",
            tags = setOf("android", "email", "draft", "host-tool", "user-mediated"),
            parameters = listOf(
                BotToolParameter("to", SCHEMA_TYPE_STRING, "Recipient email address."),
                BotToolParameter("subject", SCHEMA_TYPE_STRING, "Draft email subject."),
                BotToolParameter("body", SCHEMA_TYPE_STRING, "Draft email body."),
            ),
        ),
        "android_send_sms_draft" to BotToolDefinition(
            name = "android_send_sms_draft",
            description = "Open the SMS app with a draft message for the phone number. This does not send the SMS.",
            tags = setOf("android", "sms", "draft", "host-tool", "user-mediated"),
            parameters = listOf(
                BotToolParameter("phone_number", SCHEMA_TYPE_STRING, "Recipient phone number."),
                BotToolParameter("body", SCHEMA_TYPE_STRING, "Draft SMS body."),
            ),
        ),
        "android_create_calendar_event_draft" to BotToolDefinition(
            name = "android_create_calendar_event_draft",
            description = "Open the calendar insert UI with a draft event. The user reviews and saves it manually.",
            tags = setOf("android", "calendar", "draft", "host-tool", "user-mediated"),
            parameters = listOf(
                BotToolParameter("title", SCHEMA_TYPE_STRING, "Calendar event title."),
                BotToolParameter("datetime", SCHEMA_TYPE_STRING, "Event start datetime as ISO-8601, for example 2026-05-02T14:30:00."),
            ),
        ),
        "android_create_contact_draft" to BotToolDefinition(
            name = "android_create_contact_draft",
            description = "Open the contacts insert UI with a draft contact. The user reviews and saves it manually.",
            tags = setOf("android", "contacts", "draft", "host-tool", "user-mediated"),
            parameters = listOf(
                BotToolParameter("first_name", SCHEMA_TYPE_STRING, "Contact first name."),
                BotToolParameter("last_name", SCHEMA_TYPE_STRING, "Contact last name."),
                BotToolParameter("phone_number", SCHEMA_TYPE_STRING, "Optional contact phone number.", required = false),
                BotToolParameter("email", SCHEMA_TYPE_STRING, "Optional contact email address.", required = false),
            ),
        ),
        "android_set_flashlight" to BotToolDefinition(
            name = "android_set_flashlight",
            description = "Turn the Android flashlight on or off when a camera flash is available. This directly changes device state and returns capability/audit metadata in the result.",
            tags = setOf("android", "flashlight", "device-state", "host-tool"),
            parameters = listOf(BotToolParameter("enabled", SCHEMA_TYPE_BOOLEAN, "True to turn the flashlight on, false to turn it off.")),
        ),
        "render_summary_card" to BotToolDefinition(
            name = "render_summary_card",
            description = "Render a structured summary card in the Android client chat UI",
            tags = setOf("android", "ui", "generated-ui"),
        ),
        "render_metric_card" to BotToolDefinition(
            name = "render_metric_card",
            description = "Render a structured metric card in the Android client chat UI",
            tags = setOf("android", "ui", "generated-ui"),
        ),
        "render_suggestion_chips" to BotToolDefinition(
            name = "render_suggestion_chips",
            description = "Render suggestion chips in the Android client chat UI that can send follow-up messages",
            tags = setOf("android", "ui", "generated-ui", "interactive"),
        ),
    )

    fun isSupported(toolName: String): Boolean = toolName in definitions

    fun listDefinitions(toolNames: Set<String>? = null): List<BotToolDefinition> {
        return definitions.values.filterByRequestedNames(toolNames)
    }

    fun listToolCreateParams(toolNames: Set<String>? = null): List<ToolCreateParams> =
        listDefinitions(toolNames).map { definition ->
            ToolCreateParams(
                sourceCode = buildStubSource(definition),
                jsonSchema = buildJsonSchema(definition),
                description = definition.description,
                tags = definition.tags.toList(),
            )
        }

    suspend fun execute(toolName: String, arguments: String?): BotToolExecutionResult {
        val definition = definitions[toolName]
            ?: return BotToolExecutionResult.Unavailable(toolName, "Unsupported local bot tool")

        return when (toolName) {
            "get_battery_status" -> executeProviderTool(definition, providerId = "battery")
            "get_connectivity_status" -> executeProviderTool(definition, providerId = "connectivity")
            "get_current_time" -> executeProviderTool(definition, providerId = "time")
            "get_device_context" -> executeCombinedContext(definition)
            "launch_main_app" -> executeAndroidBridge(definition) { androidExecutionBridge.launchMainApp() }
            "launch_app" -> executeLaunchApp(definition, arguments)
            "write_clipboard" -> executeWriteClipboard(definition, arguments)
            "read_clipboard" -> executeAndroidBridge(definition) { androidExecutionBridge.readClipboard() }
            "notification_status" -> executeAndroidBridge(definition) { androidExecutionBridge.notificationStatus() }
            "list_launchable_apps" -> executeAndroidBridge(definition) { androidExecutionBridge.listLaunchableApps() }
            "android_open_wifi_settings" -> executeAndroidBridge(definition) { androidExecutionBridge.openWifiSettings() }
            "android_show_location_on_map" -> executeShowLocationOnMap(definition, arguments)
            "android_send_email_draft" -> executeSendEmailDraft(definition, arguments)
            "android_send_sms_draft" -> executeSendSmsDraft(definition, arguments)
            "android_create_calendar_event_draft" -> executeCreateCalendarEventDraft(definition, arguments)
            "android_create_contact_draft" -> executeCreateContactDraft(definition, arguments)
            "android_set_flashlight" -> executeSetFlashlight(definition, arguments)
            "render_summary_card" -> executeGeneratedUi(definition, arguments)
            "render_metric_card" -> executeGeneratedUi(definition, arguments)
            "render_suggestion_chips" -> executeGeneratedUi(definition, arguments)
            else -> BotToolExecutionResult.Unavailable(toolName, "Unsupported local bot tool")
        }
    }

    private suspend fun executeProviderTool(
        definition: BotToolDefinition,
        providerId: String,
    ): BotToolExecutionResult {
        val provider = contextProviders.firstOrNull { it.providerId == providerId }
            ?: return BotToolExecutionResult.Unavailable(definition.name, "Provider '$providerId' is not registered")

        if (!provider.hasPermission()) {
            return BotToolExecutionResult.Unavailable(
                toolName = definition.name,
                reason = "Provider '$providerId' is unavailable because required permissions are missing",
            )
        }

        return try {
            val context = provider.gatherContext()
                ?: return BotToolExecutionResult.Unavailable(definition.name, "Provider '$providerId' returned no context")

            BotToolExecutionResult.Success(
                toolName = definition.name,
                payload = buildJsonObject {
                    put("tool", definition.name)
                    put("provider_id", provider.providerId)
                    put("display_name", provider.displayName)
                    put("context", context)
                }.toString(),
            )
        } catch (e: Exception) {
            BotToolExecutionResult.Failure(definition.name, e.message ?: "Unknown tool failure")
        }
    }

    private suspend fun executeCombinedContext(definition: BotToolDefinition): BotToolExecutionResult {
        val payload = buildJsonObject {
            put("tool", definition.name)
            contextProviders.sortedBy { it.providerId }.forEach { provider ->
                val value = when {
                    !provider.hasPermission() -> JsonPrimitive("permission_missing")
                    else -> JsonPrimitive(provider.gatherContext() ?: "unavailable")
                }
                put(provider.providerId, value)
            }
        }

        return BotToolExecutionResult.Success(definition.name, payload.toString())
    }

    private fun executeAndroidBridge(
        definition: BotToolDefinition,
        action: () -> JsonObject,
    ): BotToolExecutionResult {
        return try {
            BotToolExecutionResult.Success(definition.name, action().toString())
        } catch (e: Exception) {
            BotToolExecutionResult.Failure(definition.name, e.message ?: "Unknown Android tool failure")
        }
    }

    private fun executeLaunchApp(
        definition: BotToolDefinition,
        arguments: String?,
    ): BotToolExecutionResult {
        val args = parseArguments(definition, arguments) ?: return missingArguments(definition)
        val packageName = args.requiredString("package_name")
            ?: return BotToolExecutionResult.Unavailable(definition.name, "Missing package_name argument")
        return executeAndroidBridge(definition) { androidExecutionBridge.launchApp(packageName) }
    }

    private fun executeWriteClipboard(
        definition: BotToolDefinition,
        arguments: String?,
    ): BotToolExecutionResult {
        val args = parseArguments(definition, arguments) ?: return missingArguments(definition)
        val text = args.requiredString("text")
            ?: return BotToolExecutionResult.Unavailable(definition.name, "Missing text argument")
        return executeAndroidBridge(definition) { androidExecutionBridge.writeClipboard(text) }
    }

    private fun executeShowLocationOnMap(
        definition: BotToolDefinition,
        arguments: String?,
    ): BotToolExecutionResult {
        val args = parseArguments(definition, arguments) ?: return missingArguments(definition)
        val location = args.requiredString("location")
            ?: return BotToolExecutionResult.Unavailable(definition.name, "Missing location argument")
        return executeAndroidBridge(definition) { androidExecutionBridge.showLocationOnMap(location) }
    }

    private fun executeSendEmailDraft(
        definition: BotToolDefinition,
        arguments: String?,
    ): BotToolExecutionResult {
        val args = parseArguments(definition, arguments) ?: return missingArguments(definition)
        val to = args.requiredString("to")
            ?: return BotToolExecutionResult.Unavailable(definition.name, "Missing to argument")
        val subject = args.requiredString("subject", allowBlank = true)
            ?: return BotToolExecutionResult.Unavailable(definition.name, "Missing subject argument")
        val body = args.requiredString("body", allowBlank = true)
            ?: return BotToolExecutionResult.Unavailable(definition.name, "Missing body argument")
        return executeAndroidBridge(definition) { androidExecutionBridge.sendEmailDraft(to, subject, body) }
    }

    private fun executeSendSmsDraft(
        definition: BotToolDefinition,
        arguments: String?,
    ): BotToolExecutionResult {
        val args = parseArguments(definition, arguments) ?: return missingArguments(definition)
        val phoneNumber = args.requiredString("phone_number")
            ?: return BotToolExecutionResult.Unavailable(definition.name, "Missing phone_number argument")
        val body = args.requiredString("body", allowBlank = true)
            ?: return BotToolExecutionResult.Unavailable(definition.name, "Missing body argument")
        return executeAndroidBridge(definition) { androidExecutionBridge.sendSmsDraft(phoneNumber, body) }
    }

    private fun executeCreateCalendarEventDraft(
        definition: BotToolDefinition,
        arguments: String?,
    ): BotToolExecutionResult {
        val args = parseArguments(definition, arguments) ?: return missingArguments(definition)
        val title = args.requiredString("title")
            ?: return BotToolExecutionResult.Unavailable(definition.name, "Missing title argument")
        val datetime = args.requiredString("datetime")
            ?: return BotToolExecutionResult.Unavailable(definition.name, "Missing datetime argument")
        return executeAndroidBridge(definition) { androidExecutionBridge.createCalendarEventDraft(title, datetime) }
    }

    private fun executeCreateContactDraft(
        definition: BotToolDefinition,
        arguments: String?,
    ): BotToolExecutionResult {
        val args = parseArguments(definition, arguments) ?: return missingArguments(definition)
        val firstName = args.requiredString("first_name")
            ?: return BotToolExecutionResult.Unavailable(definition.name, "Missing first_name argument")
        val lastName = args.requiredString("last_name")
            ?: return BotToolExecutionResult.Unavailable(definition.name, "Missing last_name argument")
        val phoneNumber = args.optionalString("phone_number")
        val email = args.optionalString("email")
        return executeAndroidBridge(definition) {
            androidExecutionBridge.createContactDraft(firstName, lastName, phoneNumber, email)
        }
    }

    private fun executeSetFlashlight(
        definition: BotToolDefinition,
        arguments: String?,
    ): BotToolExecutionResult {
        val args = parseArguments(definition, arguments) ?: return missingArguments(definition)
        val enabled = args["enabled"]?.jsonPrimitive?.booleanOrNull
            ?: return BotToolExecutionResult.Unavailable(definition.name, "Missing enabled boolean argument")
        return executeAndroidBridge(definition) { androidExecutionBridge.setFlashlight(enabled) }
    }

    private fun parseArguments(definition: BotToolDefinition, arguments: String?): JsonObject? {
        if (definition.parameters.isEmpty()) return buildJsonObject { }
        if (arguments.isNullOrBlank()) return null
        return runCatching { json.parseToJsonElement(arguments).jsonObject }.getOrNull()
    }

    private fun JsonObject.requiredString(key: String, allowBlank: Boolean = false): String? {
        val value = optionalString(key) ?: return null
        return value.takeIf { allowBlank || it.isNotBlank() }
    }

    private fun JsonObject.optionalString(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun missingArguments(definition: BotToolDefinition): BotToolExecutionResult =
        BotToolExecutionResult.Unavailable(definition.name, "Missing or invalid JSON arguments")

    private fun executeGeneratedUi(
        definition: BotToolDefinition,
        arguments: String?,
    ): BotToolExecutionResult {
        if (arguments.isNullOrBlank()) {
            return BotToolExecutionResult.Unavailable(definition.name, "Missing JSON arguments for generated UI")
        }

        val args = runCatching { json.parseToJsonElement(arguments).jsonObject }.getOrElse {
            return BotToolExecutionResult.Failure(definition.name, "Invalid JSON arguments")
        }

        val componentName = when (definition.name) {
            "render_summary_card" -> "summary_card"
            "render_metric_card" -> "metric_card"
            "render_suggestion_chips" -> "suggestion_chips"
            else -> return BotToolExecutionResult.Unavailable(definition.name, "Unsupported generated UI tool")
        }

        val fallbackText = args["text"]?.jsonPrimitive?.contentOrNull
            ?: args["fallback_text"]?.jsonPrimitive?.contentOrNull

        val payload = buildJsonObject {
            put("type", "generated_ui")
            put("component", componentName)
            put("props", args)
            fallbackText?.let { put("text", it) }
        }

        return BotToolExecutionResult.Success(
            toolName = definition.name,
            payload = payload.toString(),
        )
    }

    private fun buildStubSource(definition: BotToolDefinition): String {
        val params = definition.parameters.joinToString(", ") { parameter ->
            val typeName = when (parameter.type) {
                SCHEMA_TYPE_BOOLEAN -> "bool"
                else -> "str"
            }
            val defaultValue = if (parameter.required) "" else " = None"
            "${parameter.name}: $typeName$defaultValue"
        }
        return """
            def ${definition.name}($params):
                pass
        """.trimIndent()
    }

    private fun buildJsonSchema(definition: BotToolDefinition): JsonObject = buildJsonObject {
        put("name", definition.name)
        put("description", definition.description)
        put("parameters", buildJsonObject {
            put("type", SCHEMA_TYPE_OBJECT)
            put("properties", buildJsonObject {
                definition.parameters.forEach { parameter ->
                    put(parameter.name, buildJsonObject {
                        put("type", parameter.type)
                        put("description", parameter.description)
                    })
                }
            })
            put("required", JsonArray(definition.parameters.filter { it.required }.map { JsonPrimitive(it.name) }))
        })
    }

    private fun Collection<BotToolDefinition>.filterByRequestedNames(toolNames: Set<String>?): List<BotToolDefinition> {
        if (toolNames == null) return toList()
        if (toolNames.isEmpty()) return emptyList()
        return filter { it.name in toolNames }
    }
}
