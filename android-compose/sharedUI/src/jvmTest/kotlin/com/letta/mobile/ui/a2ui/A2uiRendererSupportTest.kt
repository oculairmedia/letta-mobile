package com.letta.mobile.ui.a2ui

import com.letta.mobile.data.a2ui.A2uiComponent
import com.letta.mobile.data.a2ui.A2uiSurfaceState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * letta-mobile-2don7: A2UI moved from the Android-only designsystem module
 * into sharedLogic's jvmAndAndroid source set so desktop can render the same
 * surfaces. These tests exercise the pure (non-@Composable) action/catalog
 * resolution helpers that now live in shared code, run on both the Android
 * and desktop (jvmTest) targets, and guard the wire contract documented in
 * the A2UI README (button action shape, `openUrl` allowlist, spacing/status
 * catalog vocabulary).
 */
class A2uiRendererSupportTest {

    private val emptyScope = A2uiRenderScope.Root

    private fun surface(components: Map<String, A2uiComponent> = emptyMap()) = A2uiSurfaceState(
        surfaceId = "surface-1",
        rootComponentId = components.keys.firstOrNull(),
        components = components,
    )

    @Test
    fun `action resolves name context and stable action id`() {
        val raw = buildJsonObject {
            put(
                "action",
                buildJsonObject {
                    put("name", "issue.open")
                    put("actionId", "open-1")
                    put(
                        "context",
                        buildJsonObject { put("issueId", JsonPrimitive("42")) },
                    )
                },
            )
        }
        val component = A2uiComponent(id = "openIssue", component = "Button", raw = raw)

        val action = component.action(surface(), emptyScope)

        assertEquals("issue.open", action?.name)
        assertEquals("open-1", action?.actionId)
        assertEquals("42", (action?.context?.get("issueId") as? JsonPrimitive)?.content)
    }

    @Test
    fun `action returns null when no action or onClick block is present`() {
        val component = A2uiComponent(id = "plainButton", component = "Button", raw = JsonObject(emptyMap()))

        assertNull(component.action(surface(), emptyScope))
    }

    private fun componentWithOpenUrl(url: String): A2uiComponent {
        val raw = buildJsonObject {
            put(
                "action",
                buildJsonObject {
                    put(
                        "functionCall",
                        buildJsonObject {
                            put("call", JsonPrimitive("openUrl"))
                            put("args", buildJsonObject { put("url", JsonPrimitive(url)) })
                        },
                    )
                },
            )
        }
        return A2uiComponent(id = "linkButton", component = "Button", raw = raw)
    }

    @Test
    fun `localOpenUrl accepts an https target`() {
        val component = componentWithOpenUrl("https://letta.com/docs")

        assertEquals("https://letta.com/docs", component.localOpenUrl(surface(), emptyScope))
    }

    @Test
    fun `localOpenUrl rejects a non-http scheme`() {
        val component = componentWithOpenUrl("javascript:alert(1)")

        assertNull(component.localOpenUrl(surface(), emptyScope))
    }

    @Test
    fun `spacing tokens resolve to the documented dp scale`() {
        fun spacingOf(token: String) = A2uiComponent(
            id = "row",
            component = "Row",
            raw = buildJsonObject { put("spacing", JsonPrimitive(token)) },
        ).spacing()

        assertEquals(0f, spacingOf("none").value)
        assertEquals(4f, spacingOf("xs").value)
        assertEquals(8f, spacingOf("sm").value)
        assertEquals(12f, spacingOf("md").value)
        assertEquals(16f, spacingOf("lg").value)
        assertEquals(24f, spacingOf("xl").value)
    }

    @Test
    fun `schedule status maps known wire values and falls back to Active`() {
        assertEquals(ScheduleStatus.Active, ScheduleStatus.from("running"))
        assertEquals(ScheduleStatus.Paused, ScheduleStatus.from("disabled"))
        assertEquals(ScheduleStatus.Failed, ScheduleStatus.from("error"))
        assertEquals(ScheduleStatus.Idle, ScheduleStatus.from("pending"))
        assertEquals(ScheduleStatus.Active, ScheduleStatus.from("unrecognized-value"))
    }

    @Test
    fun `tool approval risk maps known wire values and falls back to Medium`() {
        assertEquals(ToolApprovalRisk.Low, ToolApprovalRisk.from("low"))
        assertEquals(ToolApprovalRisk.Destructive, ToolApprovalRisk.from("destructive"))
        assertEquals(ToolApprovalRisk.Medium, ToolApprovalRisk.from(null))
        assertEquals(ToolApprovalRisk.Medium, ToolApprovalRisk.from("unrecognized-value"))
    }
}
