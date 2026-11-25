package com.letta.mobile.ui.shell.header

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.shell.SessionShellChatPort
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.data.shell.ShellSettingsSection
import com.letta.mobile.data.shell.ShellWorkspaceNavigator
import com.letta.mobile.data.shell.ShellWorkspaceState
import com.letta.mobile.ui.search.LettaSearchRow
import com.letta.mobile.ui.search.LettaSearchSection
import com.letta.mobile.ui.settings.defaultUnifiedSearchOptions
import com.letta.mobile.ui.shell.workspace.openSearchHit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Settings options in the unified search (a search outside the Settings page still finds
 * "wallpaper", notifications…), on both surfaces, and a pick opens Settings on the option's group.
 */
class UnifiedSettingsSearchIntegrationTest {
    private val config = LettaConfig(id = "c", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "https://letta.example")

    /** The options a host that binds everything (an Android 12+ shell) lists. */
    private val sources = UnifiedSearchSources(settings = catalog(dynamicColor = true, notifications = true, providers = true))

    private fun catalog(dynamicColor: Boolean = false, notifications: Boolean = false, providers: Boolean = false) =
        defaultUnifiedSearchOptions(config, dynamicColor, notifications = notifications, providers = providers)

    @Test
    fun aSettingIsSearchableOnBothSurfacesWithItsSectionAndGroup() {
        val header = UnifiedShellSearch.sections(UnifiedSearchQuery("wallpaper"), sources)
        val palette = UnifiedShellSearch.sections(UnifiedSearchQuery("wallpaper", surface = UnifiedSearchSurface.Palette), sources)

        assertEquals(listOf("Dynamic colour"), header.settingsLabels())
        assertEquals(header.settingsLabels(), palette.settingsLabels())
        val row = header.single { it.title == "Settings" }.rows.single()
        assertEquals("Appearance", row.sublabel)
        assertEquals(UnifiedSearchHit(UnifiedSearchKind.Settings, "Appearance"), UnifiedShellSearch.hitOf(row))
    }

    /**
     * The page only draws the dynamic-colour row where its binding exists (desktop, the web and
     * Android before 12 bind none), so a host that binds none does not offer the row to find.
     */
    @Test
    fun aHostThatBindsNoDynamicColourDoesNotOfferIt() {
        val unbound = UnifiedSearchSources(settings = catalog())

        val header = UnifiedShellSearch.sections(UnifiedSearchQuery("wallpaper"), unbound)
        val palette = UnifiedShellSearch.sections(UnifiedSearchQuery("wallpaper", surface = UnifiedSearchSurface.Palette), unbound)

        assertTrue(header.none { it.title == "Settings" })
        assertTrue(palette.none { it.title == "Settings" })
    }

    @Test
    fun bothSurfacesCoverTheSettingsKind() {
        assertTrue(UnifiedSearchSurface.Header.kinds.contains(UnifiedSearchKind.Settings))
        assertTrue(UnifiedSearchSurface.Palette.kinds.contains(UnifiedSearchKind.Settings))
        assertTrue(UnifiedShellSearch.scopes.any { it.id == UnifiedSearchKind.Settings.id })
    }

    /** The option's helper and its section are matched too, like every other kind. */
    @Test
    fun helperAndSectionTextMatch() {
        val byHelper = UnifiedShellSearch.sections(UnifiedSearchQuery("wallpaper"), sources)
        assertEquals(listOf("Dynamic colour"), byHelper.settingsLabels())

        val bySection = UnifiedShellSearch.sections(UnifiedSearchQuery("notifications"), sources)
        assertEquals(listOf("Notify me when an agent needs approval"), bySection.settingsLabels())
    }

    /** An options row works like an agents row: an empty query finds them all, capped per section. */
    @Test
    fun aBlankQueryListsSettingsRows() {
        assertTrue(UnifiedShellSearch.sections(UnifiedSearchQuery(""), sources).settingsLabels().isNotEmpty())
    }

    /** Picking a rendered row navigates to Settings on the target group's anchor. */
    @Test
    fun pickingASettingsHitOpensItsGroupOnTheSettingsPage() {
        val workspace = ShellWorkspaceState(ShellDestination.Home)
        val navigator = ShellWorkspaceNavigator(workspace, SessionShellChatPort())
        val row = LettaSearchRow(UnifiedShellSearch.rowId(UnifiedSearchKind.Settings, "Chat"), "")

        navigator.openSearchHit(UnifiedShellSearch.hitOf(row)!!)

        assertEquals(ShellDestination.Settings(ShellSettingsSection.Chat), workspace.page)
    }

    /** The search holds no Settings options when no host catalog was given; the kind stays inert. */
    @Test
    fun withoutSettingsOptionsThereIsNoSettingsSection() {
        assertTrue(UnifiedShellSearch.sections(UnifiedSearchQuery("wallpaper"), UnifiedSearchSources()).none { it.title == "Settings" })
    }

    /** A malformed or unknown group id is a row the shell never renders; it opens nothing. */
    @Test
    fun anUnknownGroupOpensNothing() {
        val workspace = ShellWorkspaceState(ShellDestination.Home)
        val navigator = ShellWorkspaceNavigator(workspace, SessionShellChatPort())

        navigator.openSearchHit(UnifiedSearchHit(UnifiedSearchKind.Settings, "not-a-group"))

        assertEquals(ShellDestination.Home, workspace.page)
    }

    private fun List<LettaSearchSection>.settingsLabels() =
        single { it.title == "Settings" }.rows.map { it.label }
}

/** The standard catalog hosts share: the Settings options a search outside the page can find. */
class DefaultUnifiedSearchOptionsTest {
    @Test
    fun theCatalogListsTheStandardRows() {
        val options = defaultUnifiedSearchOptions(null, notifications = true, providers = true)

        assertTrue(options.any { it.title == "Theme" && it.group == "Appearance" })
        assertTrue(options.any { it.title == "Permission mode" && it.group == "Chat" })
        assertTrue(options.any { it.title == "Notify me when an agent needs approval" && it.group == "Notifications" })
        assertTrue(options.any { it.title == "Manage providers" && it.group == "Providers" })
    }

    /** The dynamic-colour row draws on a host that has wallpaper colours, and only there. */
    @Test
    fun dynamicColourIsListedOnlyWhereTheHostBindsIt() {
        val unbound = defaultUnifiedSearchOptions(null)
        val bound = defaultUnifiedSearchOptions(null, dynamicColor = true)

        assertTrue(unbound.none { it.helper.orEmpty().contains("wallpaper") })
        assertTrue(bound.any { it.helper.orEmpty().contains("wallpaper") })
    }

    @Test
    fun sectionsTheHostDoesNotBindAreNotListed() {
        val options = defaultUnifiedSearchOptions(null, dynamicColor = true)

        assertTrue(options.none { it.title == "Notify me when an agent needs approval" })
        assertTrue(options.none { it.title == "Manage providers" })
    }

    @Test
    fun aSelfHostedConfigAddsItsFormRowsAndASavedTokenItsDangerRow() {
        val selfHosted = LettaConfig(id = "c", mode = LettaConfig.Mode.SELF_HOSTED, serverUrl = "https://letta.example")
        assertTrue(defaultUnifiedSearchOptions(selfHosted).any { it.title == "Host address" })
        assertTrue(defaultUnifiedSearchOptions(selfHosted).none { it.title == "Remove access token" })

        val withToken = selfHosted.copy(accessToken = "tok")

        assertTrue(defaultUnifiedSearchOptions(withToken).any { it.title == "Access token" })
        assertTrue(defaultUnifiedSearchOptions(withToken).any { it.title == "Remove access token" && it.group == "Danger" })
    }
}
