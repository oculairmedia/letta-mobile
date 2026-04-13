package com.letta.mobile.bot.tools

import com.letta.mobile.bot.context.DeviceContextProvider
import com.letta.mobile.data.model.ToolCreateParams
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class BotToolDefinition(
    val name: String,
    val description: String,
    val tags: Set<String> = emptySet(),
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
        ),
        "write_clipboard" to BotToolDefinition(
            name = "write_clipboard",
            description = "Write text to the Android clipboard",
            tags = setOf("android", "clipboard"),
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

    fun listDefinitions(): List<BotToolDefinition> = definitions.values.toList()

    fun listToolCreateParams(): List<ToolCreateParams> = definitions.values.map { definition ->
        ToolCreateParams(
            sourceCode = buildStubSource(definition),
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
        val packageName = parseStringArg(arguments, "package_name")
            ?: return BotToolExecutionResult.Unavailable(definition.name, "Missing package_name argument")
        return executeAndroidBridge(definition) { androidExecutionBridge.launchApp(packageName) }
    }

    private fun executeWriteClipboard(
        definition: BotToolDefinition,
        arguments: String?,
    ): BotToolExecutionResult {
        val text = parseStringArg(arguments, "text")
            ?: return BotToolExecutionResult.Unavailable(definition.name, "Missing text argument")
        return executeAndroidBridge(definition) { androidExecutionBridge.writeClipboard(text) }
    }

    private fun parseStringArg(arguments: String?, key: String): String? {
        if (arguments.isNullOrBlank()) return null
        return runCatching {
            json.parseToJsonElement(arguments).jsonObject[key]?.jsonPrimitive?.contentOrNull
        }.getOrNull()
    }

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

    private fun buildStubSource(definition: BotToolDefinition): String = """
        def ${definition.name}():
            pass
    """.trimIndent()
}
