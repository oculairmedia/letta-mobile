@file:OptIn(ExperimentalTestApi::class)

package com.letta.mobile.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellPageKind
import com.letta.mobile.data.shell.ShellSettingsSection
import com.letta.mobile.data.shell.ShellSkillsSection
import com.letta.mobile.ui.shell.pages.projects.ProjectsPageTags
import com.letta.mobile.ui.shell.pages.skills.SkillsPageActions
import com.letta.mobile.ui.shell.pages.skills.SkillsPageSection
import com.letta.mobile.ui.shell.pages.skills.SkillsPageState
import com.letta.mobile.ui.shell.pages.skills.SkillsPageTags
import kotlin.test.Test
import kotlin.test.assertEquals

/** letta-mobile-ckmub.3.4: ShellPage is the one destination -> page switch. */
class ShellPageTest {
    private val destinations: List<ShellDestination> = listOf(
        ShellDestination.Home,
        ShellDestination.Chat(agentId = "agent-1", conversationId = "conv-1"),
        ShellDestination.Canvas(canvasId = "board-1"),
        ShellDestination.Memory("agent-1"),
        ShellDestination.Schedules("agent-1"),
        ShellDestination.Channels,
        ShellDestination.Skills(section = ShellSkillsSection.Tools),
        ShellDestination.Settings(ShellSettingsSection.Pairing),
        ShellDestination.AgentEditor("agent-1"),
        ShellDestination.Providers,
        ShellDestination.Overview,
        ShellDestination.Runs,
        ShellDestination.Templates,
        ShellDestination.Projects,
    )

    /** Fake pages: each tags itself with its kind and records the destination it was handed. */
    private class FakePages {
        val seen = mutableListOf<ShellDestination>()

        fun <D : ShellDestination> page(kind: ShellPageKind) = ShellPageContent<D> { destination, modifier ->
            seen += destination
            Box(modifier.fillMaxSize().testTag(tag(kind)))
        }

        val fallback = ShellPageContent<ShellDestination> { destination, modifier ->
            seen += destination
            Box(modifier.fillMaxSize().testTag(FALLBACK))
        }

        fun all() = ShellPageDeps(
            home = page(ShellPageKind.Home),
            chat = page(ShellPageKind.Chat),
            canvas = page(ShellPageKind.Canvas),
            memory = page(ShellPageKind.Memory),
            schedules = page(ShellPageKind.Schedules),
            channels = page(ShellPageKind.Channels),
            skills = page(ShellPageKind.Skills),
            settings = page(ShellPageKind.Settings),
            agentEditor = page(ShellPageKind.AgentEditor),
            providers = page(ShellPageKind.Providers),
            admin = ShellAdminPages(
                runs = page(ShellPageKind.Runs),
                templates = page(ShellPageKind.Templates),
                projects = page(ShellPageKind.Projects),
            ),
            fallback = fallback,
        )
    }

    @Test
    fun everyDestinationRendersItsOwnPageWithTheDestination() = runComposeUiTest {
        val fakes = FakePages()
        val deps = fakes.all()
        var current by mutableStateOf(destinations.first())
        setContent { MaterialTheme { ShellPage(current, deps, Modifier.width(400.dp).height(600.dp)) } }
        for (destination in destinations) {
            current = destination
            waitForIdle()
            // S10: Overview is the Settings page on its About section.
            val overview = destination.kind == ShellPageKind.Overview
            onNodeWithTag(tag(if (overview) ShellPageKind.Settings else destination.kind)).assertExists()
            assertEquals(if (overview) ShellDestination.Settings(ShellSettingsSection.About) else destination, fakes.seen.last())
        }
    }

    @Test
    fun aDestinationWithoutAPageGoesToThePlatformFallback() = runComposeUiTest {
        val fakes = FakePages()
        val deps = ShellPageDeps(home = fakes.page(ShellPageKind.Home), fallback = fakes.fallback)
        assertEquals(setOf(ShellPageKind.Home), deps.registered)
        var current by mutableStateOf<ShellDestination>(ShellDestination.Home)
        setContent { MaterialTheme { ShellPage(current, deps) } }
        onNodeWithTag(tag(ShellPageKind.Home)).assertExists()
        for (destination in destinations.filter { it != ShellDestination.Home }) {
            current = destination
            waitForIdle()
            onNodeWithTag(FALLBACK).assertExists()
            assertEquals(destination, fakes.seen.last())
        }
    }

    @Test
    fun overviewIsRegisteredOnlyWithASettingsPage() {
        val fakes = FakePages()
        assertEquals(true, ShellPageKind.Overview in fakes.all().registered)
        assertEquals(false, ShellPageKind.Overview in ShellPageDeps(home = fakes.page(ShellPageKind.Home)).registered)
    }

    @Test
    fun withNoFallbackAnUnboundDestinationIsAnEmptyPage() = runComposeUiTest {
        setContent { MaterialTheme { ShellPage(ShellDestination.Overview, ShellPageDeps()) } }
        onNodeWithTag(ShellPageTags.EMPTY).assertExists()
    }

    /** letta-mobile-ckmub.2.6: a backend without projects says so on the Projects destination. */
    @Test
    fun theProjectsUnavailableBindingExplainsAndOffersSettings() = runComposeUiTest {
        var opened = 0
        val deps = ShellPageDeps(admin = ShellAdminPages(projects = shellProjectsUnavailablePage(onOpenSettings = { opened++ })))
        setContent { MaterialTheme { ShellPage(ShellDestination.Projects, deps, Modifier.width(400.dp).height(600.dp)) } }
        onNodeWithTag(ProjectsPageTags.UNAVAILABLE).assertExists()
        onNodeWithText("Projects need an Iroh host").assertExists()
        onNodeWithText("Open Settings").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun theSkillsBindingDrawsTheSharedPageOnTheDestinationsSection() = runComposeUiTest {
        val deps = ShellPageDeps(skills = shellSkillsPage(SkillsPageState(), SkillsPageActions()))
        var current by mutableStateOf(ShellDestination.Skills(section = ShellSkillsSection.Tools))
        setContent {
            MaterialTheme {
                Box(Modifier.width(400.dp).height(800.dp)) { ShellPage(current, deps) }
            }
        }
        onNodeWithTag(SkillsPageTags.PAGE).assertExists()
        current = ShellDestination.Skills()
        waitForIdle()
        onNodeWithTag(SkillsPageTags.PAGE).assertExists()
    }

    @Test
    fun skillsSectionsMapBothWays() {
        for (section in ShellSkillsSection.entries) assertEquals(section, section.toPageSection().toShellSection())
        assertEquals(SkillsPageSection.Tools, ShellSkillsSection.Tools.toPageSection())
    }

    private companion object {
        const val FALLBACK = "fake_fallback"

        fun tag(kind: ShellPageKind) = "fake_${kind.name}"
    }
}
