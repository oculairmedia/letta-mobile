package com.letta.mobile.feature.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.letta.mobile.data.model.AppTheme
import com.letta.mobile.data.model.ThemePreset
import com.letta.mobile.ui.theme.LettaTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.jupiter.api.Tag
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Tag("integration")
class AgentScaffoldProjectContextTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun projectContextCardShowsNameAndIdentifier() {
        var expanded by mutableStateOf(false)
        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                ProjectContextCard(
                    project = ProjectChatContext(
                        identifier = "pctx-test-id",
                        name = "My Project",
                    ),
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                )
            }
        }

        composeRule.onNodeWithText("My Project").assertIsDisplayed()
        composeRule.onNodeWithText("pctx-test-id").assertIsDisplayed()
    }

    @Test
    fun projectContextCardExpandShowsDetails() {
        var expanded by mutableStateOf(false)
        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                ProjectContextCard(
                    project = ProjectChatContext(
                        identifier = "expand-test",
                        name = "Expand Project",
                        filesystemPath = "/home/projects/expand",
                        gitUrl = "https://git.example.com/expand",
                        activeCodingAgents = "3 agents",
                        lastSyncAt = "2026-05-01T12:00:00Z",
                    ),
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                )
            }
        }

        composeRule.onNodeWithText("Details").performClick()
        composeRule.onNodeWithText("Path").assertIsDisplayed()
        composeRule.onNodeWithText("/home/projects/expand").assertIsDisplayed()
        composeRule.onNodeWithText("Git URL").assertIsDisplayed()
        composeRule.onNodeWithText("https://git.example.com/expand").assertIsDisplayed()
        composeRule.onNodeWithText("Active coding agents").assertIsDisplayed()
        composeRule.onNodeWithText("3 agents").assertIsDisplayed()
    }

    @Test
    fun projectContextCardCollapseHidesDetails() {
        var expanded by mutableStateOf(false)
        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                ProjectContextCard(
                    project = ProjectChatContext(
                        identifier = "collapse-test",
                        name = "Collapse Project",
                        gitUrl = "https://git.example.com/collapse",
                    ),
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                )
            }
        }

        composeRule.onNodeWithText("Details").performClick()
        composeRule.onNodeWithText("Git URL").assertIsDisplayed()

        composeRule.onNodeWithText("Hide").performClick()
        composeRule.onNodeWithText("Details").assertIsDisplayed()
    }

    @Test
    fun projectInfoLineShowsUnknownForMissingValue() {
        var expanded by mutableStateOf(false)
        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                ProjectContextCard(
                    project = ProjectChatContext(
                        identifier = "missing-test",
                        name = "Missing Fields",
                        gitUrl = null,
                    ),
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                )
            }
        }

        composeRule.onNodeWithText("Details").performClick()
        composeRule.onNodeWithText("Path").assertIsDisplayed()
        composeRule.onNodeWithText("Unknown").assertIsDisplayed()
    }

    @Test
    fun projectAgentsCardShowsLoadingState() {
        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                ProjectAgentsCard(
                    state = ProjectAgentsUiState(isLoading = true),
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithText("Active agents").assertIsDisplayed()
        composeRule.onNodeWithText("Loading current agent activity…").assertIsDisplayed()
    }

    @Test
    fun projectAgentsCardShowsEmptyState() {
        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                ProjectAgentsCard(
                    state = ProjectAgentsUiState(isLoading = false, agents = persistentListOf()),
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithText("No linked coding agents are currently visible for this project.").assertIsDisplayed()
    }

    @Test
    fun projectAgentsCardShowsErrorState() {
        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                ProjectAgentsCard(
                    state = ProjectAgentsUiState(error = "Connection refused"),
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithText("Connection refused").assertIsDisplayed()
    }

    @Test
    fun projectAgentsCardShowsAgentEntry() {
        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                ProjectAgentsCard(
                    state = ProjectAgentsUiState(
                        agents = persistentListOf(
                            ProjectAgentActivity(
                                id = "agent-1",
                                name = "CodingAgent",
                                statusLabel = "idle",
                                statusTone = ProjectAgentStatusTone.Neutral,
                                model = "gpt-4",
                                lastActivity = "2026-05-01T12:00:00Z",
                            ),
                        ),
                    ),
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithText("CodingAgent").assertIsDisplayed()
        composeRule.onNodeWithText("idle").assertIsDisplayed()
    }

    @Test
    fun projectBriefCardShowsLoadingState() {
        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                ProjectBriefCard(
                    brief = ProjectBriefUiState(isLoading = true),
                    onRetry = {},
                    onSaveSection = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("Living project brief").assertIsDisplayed()
    }

    @Test
    fun projectBriefCardShowsEmptyState() {
        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                ProjectBriefCard(
                    brief = ProjectBriefUiState(isLoading = false, sections = persistentMapOf()),
                    onRetry = {},
                    onSaveSection = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("Living project brief").assertIsDisplayed()
    }

    @Test
    fun projectBriefCardShowsSections() {
        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                ProjectBriefCard(
                    brief = ProjectBriefUiState(
                        sections = persistentMapOf(
                            ProjectBriefSectionKey.Description to ProjectBriefSection(
                                key = ProjectBriefSectionKey.Description,
                                blockLabel = "description",
                                content = "# Project overview\n\nThis is a test project.",
                                updatedAt = "2026-05-01T12:00:00Z",
                            ),
                        ),
                    ),
                    onRetry = {},
                    onSaveSection = { _, _ -> },
                )
            }
        }

        composeRule.onNodeWithText("Living project brief").assertIsDisplayed()
        composeRule.onNodeWithText("Project description").assertIsDisplayed()
    }

    @Test
    fun projectBugReportSummaryCardShowsTitle() {
        composeRule.setContent {
            LettaTheme(
                appTheme = AppTheme.LIGHT,
                themePreset = ThemePreset.DEFAULT,
                dynamicColor = false,
            ) {
                ProjectBugReportSummaryCard(
                    state = ProjectBugReportUiState(),
                    onCreateReport = {},
                )
            }
        }

        composeRule.onNodeWithText("Structured bug reporting").assertIsDisplayed()
    }
}
