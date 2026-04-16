package com.letta.mobile.bot.tools

import com.letta.mobile.bot.context.DeviceContextProvider
import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.runBlocking

class BotToolRegistryTest : WordSpec({
    "BotToolRegistry" should {
        "expose expected built-in tool definitions" {
            val registry = BotToolRegistry(
                contextProviders = setOf(
                    FakeDeviceContextProvider("battery", "Battery", "Battery: 80%"),
                    FakeDeviceContextProvider("connectivity", "Connectivity", "Network: WiFi"),
                    FakeDeviceContextProvider("time", "Time", "Current time: noon"),
                ),
                androidExecutionBridge = FakeAndroidExecutionBridge(),
            )

            val toolNames = registry.listDefinitions().map { it.name }

            toolNames shouldContainAll listOf(
                "get_battery_status",
                "get_connectivity_status",
                "get_current_time",
                "get_device_context",
                "launch_main_app",
                "launch_app",
                "write_clipboard",
                "read_clipboard",
                "notification_status",
                "list_launchable_apps",
                "render_summary_card",
                "render_metric_card",
                "render_suggestion_chips",
            )
        }

        "filter tool create params to requested tool names" {
            val registry = BotToolRegistry(
                contextProviders = emptySet(),
                androidExecutionBridge = FakeAndroidExecutionBridge(),
            )

            val params = registry.listToolCreateParams(setOf("get_current_time", "render_summary_card"))
            val derivedNames = params.mapNotNull { Regex("def\\s+([A-Za-z_][A-Za-z0-9_]*)").find(it.sourceCode)?.groupValues?.get(1) }

            derivedNames shouldContainAll listOf("get_current_time", "render_summary_card")
            derivedNames.size shouldBe 2
        }

        "execute provider-backed tools successfully" {
            val registry = BotToolRegistry(
                contextProviders = setOf(
                    FakeDeviceContextProvider("battery", "Battery", "Battery: 80%"),
                ),
                androidExecutionBridge = FakeAndroidExecutionBridge(),
            )

            val result = runBlocking { registry.execute("get_battery_status", null) }

            (result is BotToolExecutionResult.Success) shouldBe true
            (result as BotToolExecutionResult.Success).payload shouldContain "Battery: 80%"
        }

        "report unavailable when provider permission is missing" {
            val registry = BotToolRegistry(
                contextProviders = setOf(
                    FakeDeviceContextProvider(
                        providerId = "connectivity",
                        displayName = "Connectivity",
                        context = "Network: WiFi",
                        hasPermission = false,
                    )
                ),
                androidExecutionBridge = FakeAndroidExecutionBridge(),
            )

            val result = runBlocking { registry.execute("get_connectivity_status", null) }

            (result is BotToolExecutionResult.Unavailable) shouldBe true
            (result as BotToolExecutionResult.Unavailable).reason shouldContain "permissions"
        }

        "return combined payload for device context tool" {
            val registry = BotToolRegistry(
                contextProviders = setOf(
                    FakeDeviceContextProvider("battery", "Battery", "Battery: 80%"),
                    FakeDeviceContextProvider("time", "Time", "Current time: noon"),
                ),
                androidExecutionBridge = FakeAndroidExecutionBridge(),
            )

            val result = runBlocking { registry.execute("get_device_context", null) }

            (result is BotToolExecutionResult.Success) shouldBe true
            val payload = (result as BotToolExecutionResult.Success).payload
            payload shouldContain "battery"
            payload shouldContain "Current time: noon"
        }

        "execute clipboard write tool with JSON arguments" {
            val registry = BotToolRegistry(
                contextProviders = emptySet(),
                androidExecutionBridge = FakeAndroidExecutionBridge(),
            )

            val result = runBlocking { registry.execute("write_clipboard", """{"text":"hello world"}""") }

            (result is BotToolExecutionResult.Success) shouldBe true
            (result as BotToolExecutionResult.Success).payload shouldContain "chars_written"
        }

        "report unavailable when launch_app arguments are missing" {
            val registry = BotToolRegistry(
                contextProviders = emptySet(),
                androidExecutionBridge = FakeAndroidExecutionBridge(),
            )

            val result = runBlocking { registry.execute("launch_app", "{}") }

            (result is BotToolExecutionResult.Unavailable) shouldBe true
            (result as BotToolExecutionResult.Unavailable).reason shouldContain "package_name"
        }

        "return generated ui envelope for summary card tools" {
            val registry = BotToolRegistry(
                contextProviders = emptySet(),
                androidExecutionBridge = FakeAndroidExecutionBridge(),
            )

            val result = runBlocking {
                registry.execute(
                    "render_summary_card",
                    """{"title":"Today","body":"3 tasks pending","items":["Review inbox","Check runs"]}""",
                )
            }

            (result is BotToolExecutionResult.Success) shouldBe true
            val payload = Json.parseToJsonElement((result as BotToolExecutionResult.Success).payload).jsonObject
            payload["type"]?.jsonPrimitive?.content shouldBe "generated_ui"
            payload["component"]?.jsonPrimitive?.content shouldBe "summary_card"
            payload["props"]?.jsonObject?.get("title")?.jsonPrimitive?.content shouldBe "Today"
        }

        "return generated ui envelope for suggestion chip tools" {
            val registry = BotToolRegistry(
                contextProviders = emptySet(),
                androidExecutionBridge = FakeAndroidExecutionBridge(),
            )

            val result = runBlocking {
                registry.execute(
                    "render_suggestion_chips",
                    """{"title":"Next steps","suggestions":[{"label":"Explain coroutines","message":"Explain Kotlin coroutines"}]}""",
                )
            }

            (result is BotToolExecutionResult.Success) shouldBe true
            val payload = Json.parseToJsonElement((result as BotToolExecutionResult.Success).payload).jsonObject
            payload["type"]?.jsonPrimitive?.content shouldBe "generated_ui"
            payload["component"]?.jsonPrimitive?.content shouldBe "suggestion_chips"
            payload["props"]?.jsonObject?.get("suggestions")?.toString() shouldContain "Explain Kotlin coroutines"
        }
    }
})

private data class FakeDeviceContextProvider(
    override val providerId: String,
    override val displayName: String,
    private val context: String?,
    private val hasPermission: Boolean = true,
) : DeviceContextProvider {
    override val requiredPermissions: List<String> = emptyList()

    override suspend fun gatherContext(): String? = context

    override suspend fun hasPermission(): Boolean = hasPermission
}

private class FakeAndroidExecutionBridge : AndroidExecutionBridge {
    override fun launchMainApp(): JsonObject = payload("launch_main_app", "success")

    override fun launchApp(packageName: String): JsonObject = buildJsonObject {
        put("status", "success")
        put("package_name", packageName)
    }

    override fun writeClipboard(text: String): JsonObject = buildJsonObject {
        put("status", "success")
        put("chars_written", text.length)
    }

    override fun readClipboard(): JsonObject = buildJsonObject {
        put("status", "success")
        put("text", "clipboard text")
    }

    override fun notificationStatus(): JsonObject = buildJsonObject {
        put("status", "success")
        put("notifications_enabled", true)
    }

    override fun listLaunchableApps(limit: Int): JsonObject = buildJsonObject {
        put("status", "success")
        put("count", 1)
    }

    private fun payload(action: String, status: String): JsonObject = buildJsonObject {
        put("action", action)
        put("status", status)
    }
}
