@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.components.foundation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.letta.mobile.data.a2ui.A2uiComponent
import com.letta.mobile.data.a2ui.A2uiSurfaceState
import com.letta.mobile.data.a2ui.LETTA_SCHEDULE_CARD_WIDGET_ID
import com.letta.mobile.data.a2ui.LETTA_TOOL_APPROVAL_WIDGET_ID
import com.letta.mobile.data.settings.SettingsRow
import com.letta.mobile.ui.a2ui.A2uiSurfaceRenderer
import com.letta.mobile.ui.chat.surface.timeline.TimelineWelcome
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.settings.SettingsHeader
import com.letta.mobile.ui.settings.SettingsSection
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.SharedMaterialTheme
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * The card surfaces at the review widths (360, 700, 760, 1200 dp) in Dark and Light, written to
 * build/card-surfaces-render/ for review: Settings sections, agent-authored (A2UI) cards, and the empty
 * conversation's starter prompts. Neutral demo text only.
 */
class CardSurfacesRenderTest {
    @Test
    fun cardSurfacesRenderAtEveryReviewWidthInBothThemes() {
        for (dark in listOf(true, false)) {
            for (width in WIDTHS) {
                render("settings-$width-${theme(dark)}.png", width, dark) { SettingsScene() }
                render("a2ui-$width-${theme(dark)}.png", width, dark) { A2uiScene() }
                render("welcome-$width-${theme(dark)}.png", width, dark) {
                    TimelineWelcome(agentName = "Demo agent", hasConversation = false, onStarterPrompt = {})
                }
            }
        }
    }

    private fun render(name: String, width: Int, dark: Boolean, scene: @Composable () -> Unit) =
        runDesktopComposeUiTest(width = width, height = HEIGHT) {
            val touch = width < LettaDimens.Pane.wideBreakpoint.value
            setContent {
                SharedMaterialTheme(darkTheme = dark) {
                    ProvideLettaHost(if (touch) LettaHost.Touch else LettaHost.Pointer) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { scene() }
                    }
                }
            }
            waitForIdle()
            val file = File("build/card-surfaces-render").apply { mkdirs() }.resolve(name)
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", file)
        }

    @Composable
    private fun SettingsScene() {
        Column(
            Modifier.padding(LettaDimens.Space.lg),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        ) {
            SettingsSection(SettingsHeader("appearance", "Appearance", "How the app looks on this device.")) {
                ToggleRow(SettingsRow.Toggle("compact", "Compact rows", checked = true, onChange = {}))
                LinkRow(SettingsRow.Link("theme", "Theme", value = "System", onClick = {}))
            }
            SettingsSection(SettingsHeader("about", "About")) {
                InfoRow(SettingsRow.Info("version", "Version", "1.0.0-demo"))
                InfoRow(SettingsRow.Info("build", "Build", "demo"))
            }
        }
    }

    @Composable
    private fun A2uiScene() {
        val components = listOf(
            A2uiComponent("root", "Column", children("card", "accordion", "schedule", "approval")),
            A2uiComponent("card", "Card", JsonObject(mapOf("child" to JsonPrimitive("cardText")))),
            text("cardText", "An agent-authored card with one line of text."),
            A2uiComponent(
                "accordion",
                "Accordion",
                buildJsonObject {
                    put(
                        "items",
                        buildJsonArray {
                            add(buildJsonObject { put("key", "a"); put("title", "Details"); put("child", "accText"); put("defaultOpen", true) })
                        },
                    )
                },
            ),
            text("accText", "Folded content inside an accordion item."),
            A2uiComponent(
                "schedule",
                LETTA_SCHEDULE_CARD_WIDGET_ID,
                buildJsonObject {
                    put("scheduleId", "demo")
                    put("name", "Daily summary")
                    put("type", "recurring")
                    put("cron", "0 9 * * *")
                    put("status", "active")
                },
            ),
            A2uiComponent(
                "approval",
                LETTA_TOOL_APPROVAL_WIDGET_ID,
                buildJsonObject {
                    put("toolName", "write_file")
                    put("callId", "call-demo")
                    put("riskLevel", "medium")
                    put("rationale", "Saves the draft to the demo folder.")
                },
            ),
        ).associateBy { it.id }
        Column(Modifier.padding(LettaDimens.Space.lg)) {
            A2uiSurfaceRenderer(surface = A2uiSurfaceState(surfaceId = "demo", rootComponentId = "root", components = components))
        }
    }

    private fun text(id: String, value: String) = A2uiComponent(id, "Text", JsonObject(mapOf("text" to JsonPrimitive(value))))

    private fun children(vararg ids: String) = JsonObject(mapOf("children" to JsonArray(ids.map(::JsonPrimitive))))

    private fun theme(dark: Boolean) = if (dark) "dark" else "light"

    private companion object {
        val WIDTHS = listOf(360, 700, 760, 1200)
        const val HEIGHT = 720
    }
}
