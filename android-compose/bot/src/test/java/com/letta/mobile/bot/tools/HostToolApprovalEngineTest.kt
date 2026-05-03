package com.letta.mobile.bot.tools

import io.kotest.core.spec.style.WordSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Tag

@Tag("unit")
class HostToolApprovalEngineTest : WordSpec({
    "DefaultHostToolApprovalEngine" should {
        "allow safe tools without prompting" {
            val engine = DefaultHostToolApprovalEngine()

            val decision = runBlocking {
                engine.evaluate(
                    HostToolApprovalRequest(
                        toolName = "get_current_time",
                        policy = HostToolApprovalPolicy.None,
                        riskLevel = HostToolRiskLevel.Low,
                    ),
                )
            }

            (decision is HostToolApprovalDecision.Allowed) shouldBe true
        }

        "require approval for destructive or root tools" {
            val engine = DefaultHostToolApprovalEngine()

            val writeDecision = runBlocking {
                engine.evaluate(
                    HostToolApprovalRequest(
                        toolName = "storage.saf.write",
                        policy = HostToolApprovalPolicy.AskEveryTime,
                        riskLevel = HostToolRiskLevel.High,
                    ),
                )
            }
            val rootDecision = runBlocking {
                engine.evaluate(
                    HostToolApprovalRequest(
                        toolName = "shell.root.run",
                        policy = HostToolApprovalPolicy.AskEveryTime,
                        riskLevel = HostToolRiskLevel.Root,
                    ),
                )
            }

            (writeDecision is HostToolApprovalDecision.RequiresApproval) shouldBe true
            (rootDecision is HostToolApprovalDecision.RequiresApproval) shouldBe true
        }

        "remember approvals per session and per scope" {
            val engine = DefaultHostToolApprovalEngine()
            val sessionRequest = HostToolApprovalRequest(
                toolName = "overlay.floating_assistant",
                policy = HostToolApprovalPolicy.RememberPerSession,
            )
            val scopedRequest = HostToolApprovalRequest(
                toolName = "storage.saf.write",
                policy = HostToolApprovalPolicy.RememberPerScope,
                scopeKey = "tree://docs",
            )

            runBlocking {
                (engine.evaluate(sessionRequest) is HostToolApprovalDecision.RequiresApproval) shouldBe true
                engine.remember(HostToolRememberedApproval(sessionRequest.toolName, sessionRequest.policy))
                (engine.evaluate(sessionRequest) as HostToolApprovalDecision.Allowed).remembered shouldBe true

                (engine.evaluate(scopedRequest) is HostToolApprovalDecision.RequiresApproval) shouldBe true
                engine.remember(HostToolRememberedApproval(scopedRequest.toolName, scopedRequest.policy, scopedRequest.scopeKey))
                (engine.evaluate(scopedRequest) as HostToolApprovalDecision.Allowed).remembered shouldBe true
            }
        }

        "redact and truncate audit event fields" {
            val engine = DefaultHostToolApprovalEngine()
            val longOutput = "x".repeat(700)

            runBlocking {
                engine.recordAudit(
                    HostToolAuditEvent(
                        toolName = "shell.local.run",
                        disposition = HostToolExecutionDisposition.Allowed,
                        riskLevel = HostToolRiskLevel.High,
                        arguments = buildJsonObject {
                            put("command", "echo hello")
                            put("environment", "TOKEN=secret")
                        },
                        result = buildJsonObject {
                            put("stdout", longOutput)
                            put("exitCode", 0)
                        },
                        redactedFieldNames = setOf("environment"),
                    ),
                )
            }

            val event = runBlocking { engine.recordedAuditEvents() }.single()
            event.arguments["environment"]?.jsonPrimitive?.content shouldBe "[REDACTED]"
            event.result["stdout"]?.jsonPrimitive?.content?.length shouldBe 513
        }
    }

    "BotToolRegistry approval gate" should {
        "return structured denied result before executing a denied command" {
            val registry = BotToolRegistry(
                contextProviders = emptySet(),
                androidExecutionBridge = FakeDenyTestAndroidExecutionBridge(),
                hostToolApprovalEngine = DenyingHostToolApprovalEngine,
            )

            val result = runBlocking { registry.execute("write_clipboard", """{"text":"secret"}""") }

            (result is BotToolExecutionResult.Denied) shouldBe true
            val payload = Json.parseToJsonElement((result as BotToolExecutionResult.Denied).payload).jsonObject
            payload["status"]?.jsonPrimitive?.content shouldBe "denied"
            payload["tool"]?.jsonPrimitive?.content shouldBe "write_clipboard"
        }

        "record redacted audit for host tool execution" {
            val engine = DefaultHostToolApprovalEngine()
            val registry = BotToolRegistry(
                contextProviders = emptySet(),
                androidExecutionBridge = FakeDenyTestAndroidExecutionBridge(),
                hostToolApprovalEngine = engine,
            )

            runBlocking { registry.execute("write_clipboard", """{"text":"secret"}""") }

            val events = runBlocking { engine.recordedAuditEvents() }
            events shouldHaveSize 1
            events.single().arguments["text"]?.jsonPrimitive?.content shouldBe "[REDACTED]"
        }
    }
})

private object DenyingHostToolApprovalEngine : HostToolApprovalEngine {
    override suspend fun evaluate(request: HostToolApprovalRequest): HostToolApprovalDecision =
        HostToolApprovalDecision.Denied("Denied by test policy")

    override suspend fun recordAudit(event: HostToolAuditEvent) = Unit

    override suspend fun remember(approval: HostToolRememberedApproval) = Unit
}

private class FakeDenyTestAndroidExecutionBridge : AndroidExecutionBridge {
    override fun launchMainApp(): JsonObject = success("launch_main_app")

    override fun launchApp(packageName: String): JsonObject = success("launch_app")

    override fun writeClipboard(text: String): JsonObject = buildJsonObject {
        put("status", "success")
        put("chars_written", text.length)
    }

    override fun readClipboard(): JsonObject = success("read_clipboard")

    override fun notificationStatus(): JsonObject = success("notification_status")

    override fun listLaunchableApps(limit: Int): JsonObject = success("list_launchable_apps")

    override fun openWifiSettings(): JsonObject = success("android_open_wifi_settings")

    override fun showLocationOnMap(location: String): JsonObject = success("android_show_location_on_map")

    override fun sendEmailDraft(to: String, subject: String, body: String): JsonObject = success("android_send_email_draft")

    override fun sendSmsDraft(phoneNumber: String, body: String): JsonObject = success("android_send_sms_draft")

    override fun createCalendarEventDraft(title: String, datetime: String): JsonObject = success("android_create_calendar_event_draft")

    override fun createContactDraft(firstName: String, lastName: String, phoneNumber: String?, email: String?): JsonObject =
        success("android_create_contact_draft")

    override fun setFlashlight(enabled: Boolean): JsonObject = success("android_set_flashlight")

    private fun success(action: String): JsonObject = buildJsonObject {
        put("status", "success")
        put("action", action)
    }
}
