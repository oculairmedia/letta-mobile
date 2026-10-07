package com.letta.mobile.desktop.home

import com.letta.mobile.data.chat.runtime.displayTitle
import com.letta.mobile.data.home.FleetConversation
import com.letta.mobile.data.home.HomePageConfig
import com.letta.mobile.data.home.HomePageController
import com.letta.mobile.data.home.HomePageState
import com.letta.mobile.data.home.HomePinnedItem
import com.letta.mobile.data.home.HomeShortcut
import com.letta.mobile.data.home.SessionHomePageSource
import com.letta.mobile.data.home.SettingsStoreHomePinStore
import com.letta.mobile.data.session.SessionRepositoryGraph
import com.letta.mobile.data.session.SessionRepositoryGraphProvider
import com.letta.mobile.data.storage.SecureSettingsStore
import com.letta.mobile.desktop.DesktopDestination
import com.letta.mobile.desktop.chat.DesktopConversationSummary
import com.letta.mobile.ui.shell.pages.home.HomePageOptions
import kotlinx.coroutines.CoroutineScope

/**
 * Desktop's binding of the shared Home page (letta-mobile-c3np7.3.11): which shortcuts the desktop
 * shell can open, where they land, and the session-graph controller behind the page. Everything
 * the page shows or decides lives in sharedLogic's HomePageController and sharedUI's HomePage.
 */
internal object DesktopHome {
    /** Shortcut -> the sidebar destination that opens it. Shortcuts absent here are not offered. */
    private val destinations: Map<HomeShortcut, DesktopDestination> = mapOf(
        HomeShortcut.CONVERSATIONS to DesktopDestination.Conversations,
        HomeShortcut.TOOLS to DesktopDestination.Agents,
        HomeShortcut.BLOCKS to DesktopDestination.Memory,
        HomeShortcut.SCHEDULES to DesktopDestination.Schedules,
        HomeShortcut.PROVIDERS to DesktopDestination.Providers,
        HomeShortcut.MODELS to DesktopDestination.Providers,
        HomeShortcut.SETTINGS to DesktopDestination.Settings,
    )

    /** What a first launch pins, before the user arranges anything. */
    private val defaultPins: List<String> = listOf(
        HomeShortcut.CONVERSATIONS,
        HomeShortcut.TOOLS,
        HomeShortcut.BLOCKS,
        HomeShortcut.SCHEDULES,
    ).map(HomePinnedItem::shortcutKey)

    private const val PIN_STORAGE_KEY = "desktop.home.pinnedItems"

    fun destinationFor(shortcut: HomeShortcut): DesktopDestination? = destinations[shortcut]

    fun <Graph : SessionRepositoryGraph> controller(
        sessionGraphProvider: SessionRepositoryGraphProvider<Graph>,
        settingsStore: SecureSettingsStore,
        scope: CoroutineScope,
    ): HomePageController = HomePageController(
        source = SessionHomePageSource(sessionGraphProvider = sessionGraphProvider, scope = scope),
        pins = SettingsStoreHomePinStore(store = settingsStore, defaults = defaultPins, storageKey = PIN_STORAGE_KEY),
        scope = scope,
        config = HomePageConfig(availableShortcuts = destinations.keys),
    )
}

/** What the Home destination draws: the controller's state plus the shell's presentation inputs. */
internal data class DesktopHomeInputs(
    val state: HomePageState,
    val options: HomePageOptions,
)

/** The fleet model's view of a desktop conversation row. */
internal fun DesktopConversationSummary.toFleetConversation(): FleetConversation = FleetConversation(
    id = id,
    agentId = agentId,
    agentName = agentName,
    title = displayTitle(),
    preview = lastMessagePreview,
    updatedAtLabel = updatedAtLabel,
)
