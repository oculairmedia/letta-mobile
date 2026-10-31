@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.runDesktopComposeUiTest
import com.letta.mobile.data.model.Tool
import com.letta.mobile.data.model.ToolId
import com.letta.mobile.data.skills.Skill
import com.letta.mobile.data.tools.ToolLibraryState
import com.letta.mobile.ui.host.LettaHost
import com.letta.mobile.ui.host.ProvideLettaHost
import com.letta.mobile.ui.modelcontrol.ModelControlUiFixtures
import com.letta.mobile.ui.modelcontrol.ModelPickerActions
import com.letta.mobile.ui.modelcontrol.ModelPickerContent
import com.letta.mobile.ui.modelcontrol.ProviderFormActions
import com.letta.mobile.ui.modelcontrol.ProviderManagementActions
import com.letta.mobile.ui.modelcontrol.ProviderSettingsActions
import com.letta.mobile.ui.modelcontrol.ProviderSettingsPane
import com.letta.mobile.ui.shell.pages.skills.SkillsPage
import com.letta.mobile.ui.shell.pages.skills.SkillsPageActions
import com.letta.mobile.ui.shell.pages.skills.SkillsPageState
import com.letta.mobile.ui.theme.SharedMaterialTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Dark renders of the surfaces the shared design-system pieces land on (section headers, pills,
 * spinners), at a phone and a desktop width, written to build/design-system-render/ for review.
 * Review artifacts, not pixel goldens (letta-mobile-yx00h).
 */
class DesignSystemRenderTest {
    @Test
    fun skillsPage() = renderWidths("skills") {
        SkillsPage(state = skillsState(), actions = SkillsPageActions())
    }

    @Test
    fun modelPicker() = renderWidths("model-picker") {
        Column { ModelPickerContent(ModelControlUiFixtures.pickerState().copy(refreshing = true), pickerActions) }
    }

    @Test
    fun providerSettings() = renderWidths("providers") {
        ProviderSettingsPane(ModelControlUiFixtures.managementState(), settingsActions)
    }

    private fun renderWidths(name: String, content: @Composable () -> Unit) {
        listOf(PHONE_PX, DESKTOP_PX).forEach { width -> render("$name-$width.png", width, content) }
    }

    private fun render(file: String, width: Int, content: @Composable () -> Unit) {
        runDesktopComposeUiTest(width = width, height = HEIGHT_PX) {
            setContent {
                SharedMaterialTheme(darkTheme = true) {
                    // A phone-width render takes the touch host (effort pills, sheets); desktop the pointer host.
                    ProvideLettaHost(if (width < DESKTOP_PX) LettaHost.Touch else LettaHost.Pointer) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
                    }
                }
            }
            waitForIdle()
            val out = File("build/design-system-render").apply { mkdirs() }.resolve(file)
            ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", out)
            assertTrue(out.length() > 0, "rendered $file")
            println("design-system render: ${out.absolutePath}")
        }
    }

    private fun skillsState() = SkillsPageState(
        skills = listOf(
            Skill(
                name = "github",
                version = "1.2.0",
                description = "Work with GitHub issues and PRs",
                author = "letta",
                tags = listOf("git", "code"),
            ),
            Skill(name = "notion", description = "Read and write Notion pages", source = "global", controllable = true),
            Skill(name = "calendar", description = "Plan the week", source = "bundled"),
        ),
        installedSkillNames = setOf("notion"),
        canManageSkills = true,
        tools = ToolLibraryState(
            tools = listOf(
                Tool(
                    id = ToolId("tool-git"),
                    name = "git_status",
                    description = "Show the working tree status",
                    toolType = "custom",
                    tags = listOf("git"),
                ),
                Tool(id = ToolId("tool-mail"), name = "send_email", description = "Send an email", toolType = "letta_core"),
            ),
            isLoading = false,
            hasMorePages = false,
        ),
    )

    private val pickerActions = ModelPickerActions(
        onSelect = {},
        onQueryChange = {},
        onToggleGroup = {},
        onRefresh = {},
        onEditModels = {},
        onEffortSelected = { _, _ -> },
    )

    private val settingsActions = ProviderSettingsActions(
        management = ProviderManagementActions(
            onRefresh = {}, onQueryChange = {}, onFilterChange = {}, onToggleExpanded = {}, onExposedChange = {},
            onProviderExposedChange = { _, _ -> }, onConnect = {}, onDisconnect = {},
            form = ProviderFormActions(onChange = {}, onSubmit = {}, onDismiss = {}),
            onConfirmDisconnect = {}, onDismissDisconnect = {},
        ),
        onOpenSignIn = {}, onCheckSignIn = {}, onDismissSignIn = {}, onEdit = {},
    )

    private companion object {
        const val PHONE_PX = 360
        const val DESKTOP_PX = 1200
        const val HEIGHT_PX = 900
    }
}
