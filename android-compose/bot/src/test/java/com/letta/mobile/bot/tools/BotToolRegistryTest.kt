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
import org.junit.jupiter.api.Tag

@Tag("unit")
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
                "android_open_wifi_settings",
                "android_show_location_on_map",
                "android_send_email_draft",
                "android_send_sms_draft",
                "android_create_calendar_event_draft",
                "android_create_contact_draft",
                "android_set_flashlight",
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

        "include JSON schemas for Android host tool arguments" {
            val registry = BotToolRegistry(
                contextProviders = emptySet(),
                androidExecutionBridge = FakeAndroidExecutionBridge(),
            )

            val params = registry.listToolCreateParams(
                setOf("android_send_sms_draft", "android_set_flashlight"),
            )
            val smsSchema = params.first {
                it.sourceCode.contains("android_send_sms_draft")
            }.jsonSchema!!.jsonObject
            val flashlightSchema = params.first {
                it.sourceCode.contains("android_set_flashlight")
            }.jsonSchema!!.jsonObject
            val flashlightEnabledSchema = flashlightSchema["parameters"]
                ?.jsonObject
                ?.get("properties")
                ?.jsonObject
                ?.get("enabled")
                ?.jsonObject

            smsSchema["name"]?.jsonPrimitive?.content shouldBe "android_send_sms_draft"
            smsSchema["parameters"]?.jsonObject?.get("required").toString() shouldContain "phone_number"
            smsSchema["parameters"]?.jsonObject?.get("required").toString() shouldContain "body"
            flashlightEnabledSchema?.get("type")?.jsonPrimitive?.content shouldBe "boolean"
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

        "execute Android host draft tools with JSON arguments" {
            val registry = BotToolRegistry(
                contextProviders = emptySet(),
                androidExecutionBridge = FakeAndroidExecutionBridge(),
            )

            val result = runBlocking {
                registry.execute(
                    "android_send_email_draft",
                    """{"to":"test@example.com","subject":"Hello","body":"Draft body"}""",
                )
            }

            (result is BotToolExecutionResult.Success) shouldBe true
            val payload = Json.parseToJsonElement((result as BotToolExecutionResult.Success).payload).jsonObject
            payload["status"]?.jsonPrimitive?.content shouldBe "success"
            payload["action"]?.jsonPrimitive?.content shouldBe "android_send_email_draft"
            payload["sends_without_user"]?.jsonPrimitive?.content shouldBe "false"
        }

        "report unavailable when Android host tool arguments are missing" {
            val registry = BotToolRegistry(
                contextProviders = emptySet(),
                androidExecutionBridge = FakeAndroidExecutionBridge(),
            )

            val result = runBlocking { registry.execute("android_set_flashlight", "{}") }

            (result is BotToolExecutionResult.Unavailable) shouldBe true
            (result as BotToolExecutionResult.Unavailable).reason shouldContain "enabled"
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

    override fun openWifiSettings(): JsonObject = payload("android_open_wifi_settings", "success")

    override fun showLocationOnMap(location: String): JsonObject = buildJsonObject {
        put("status", "success")
        put("action", "android_show_location_on_map")
        put("location", location)
    }

    override fun sendEmailDraft(to: String, subject: String, body: String): JsonObject = buildJsonObject {
        put("status", "success")
        put("action", "android_send_email_draft")
        put("to", to)
        put("subject", subject)
        put("body_chars", body.length)
        put("sends_without_user", false)
    }

    override fun sendSmsDraft(phoneNumber: String, body: String): JsonObject = buildJsonObject {
        put("status", "success")
        put("action", "android_send_sms_draft")
        put("phone_number", phoneNumber)
        put("body_chars", body.length)
        put("sends_without_user", false)
    }

    override fun createCalendarEventDraft(title: String, datetime: String): JsonObject = buildJsonObject {
        put("status", "success")
        put("action", "android_create_calendar_event_draft")
        put("title", title)
        put("datetime", datetime)
    }

    override fun createContactDraft(
        firstName: String,
        lastName: String,
        phoneNumber: String?,
        email: String?,
    ): JsonObject = buildJsonObject {
        put("status", "success")
        put("action", "android_create_contact_draft")
        put("first_name", firstName)
        put("last_name", lastName)
        put("has_phone_number", phoneNumber != null)
        put("has_email", email != null)
    }

    override fun setFlashlight(enabled: Boolean): JsonObject = buildJsonObject {
        put("status", "success")
        put("action", "android_set_flashlight")
        put("enabled", enabled)
    }

    private fun payload(action: String, status: String): JsonObject = buildJsonObject {
        put("action", action)
        put("status", status)
    }
}
