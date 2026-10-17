package com.letta.mobile.data.shell

import com.letta.mobile.data.home.HomePageConfig
import com.letta.mobile.data.home.HomePinnedItem
import com.letta.mobile.data.home.HomeShortcut

/**
 * letta-mobile-ckmub.17.5: the shell's Home shortcuts (they were desktop's `DesktopHome`): which ones
 * a shell offers, the page each opens, and what a first launch pins. Every client's Home binds these.
 */
object ShellHomeShortcuts {
    private val destinations: Map<HomeShortcut, ShellDestination> = mapOf(
        HomeShortcut.CONVERSATIONS to ShellDestination.Chat(),
        HomeShortcut.TOOLS to ShellDestination.Skills(),
        HomeShortcut.BLOCKS to ShellDestination.Memory(),
        HomeShortcut.SCHEDULES to ShellDestination.Schedules(),
        HomeShortcut.PROVIDERS to ShellDestination.Providers,
        HomeShortcut.MODELS to ShellDestination.Providers,
        HomeShortcut.SETTINGS to ShellDestination.Settings(),
        HomeShortcut.RUNS to ShellDestination.Runs,
        HomeShortcut.TEMPLATES to ShellDestination.Templates,
        HomeShortcut.PROJECTS to ShellDestination.Projects,
    )

    /** What a first launch pins, before the user arranges anything. */
    val defaultPins: List<String> = listOf(
        HomeShortcut.CONVERSATIONS,
        HomeShortcut.TOOLS,
        HomeShortcut.BLOCKS,
        HomeShortcut.SCHEDULES,
    ).map(HomePinnedItem::shortcutKey)

    /** Where the pins are kept in the host's settings store (the key desktop has always used). */
    const val PIN_STORAGE_KEY: String = "desktop.home.pinnedItems"

    /** The Home controller's config: only the shortcuts the shell can open are drawn. */
    val config: HomePageConfig = HomePageConfig(availableShortcuts = destinations.keys)

    fun destinationFor(shortcut: HomeShortcut): ShellDestination? = destinations[shortcut]
}
