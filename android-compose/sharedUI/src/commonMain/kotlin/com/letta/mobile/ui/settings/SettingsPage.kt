package com.letta.mobile.ui.settings

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.model.BackendConnectionStatus
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.data.settings.AppearanceSettings
import com.letta.mobile.data.settings.ChatSettings
import com.letta.mobile.data.settings.NotificationSettings
import com.letta.mobile.data.settings.SettingsContribution
import com.letta.mobile.data.settings.SettingsGroup
import com.letta.mobile.data.shell.ShellSettingsSection
import com.letta.mobile.ui.pairing.InstallAndPairBindings
import com.letta.mobile.ui.shell.header.HeaderControls
import com.letta.mobile.ui.shell.header.HeaderNav
import com.letta.mobile.ui.shell.header.HeaderTitle
import com.letta.mobile.ui.shell.header.ShellHeader
import com.letta.mobile.ui.shell.scaffold.LettaPageScaffold

/** What the Settings page shows: the saved backend, its connection (null hides it) and the anchor to open on. */
@Immutable
data class SettingsPageState(
    val config: LettaConfig,
    val connection: BackendConnectionStatus? = null,
    val section: ShellSettingsSection? = null,
)

/**
 * The platform services behind the page's sections. [backend] is always there; every other port
 * is optional and a missing one leaves its rows out. [contributions] are the platform's own
 * sections (the desktop's bundled runtime, Android's on-device model), placed in their group.
 */
class SettingsPageServices(
    val backend: BackendSettingsActions,
    val pairing: InstallAndPairBindings? = null,
    val appearance: AppearanceSettings? = null,
    val chat: ChatSettings? = null,
    val notifications: NotificationSettings? = null,
    val onOpenProviders: (() -> Unit)? = null,
    val contributions: List<SettingsContribution> = emptyList(),
) {
    /**
     * The groups the page draws now, each with the platform sections it holds: a group shows when it
     * has a standard section or a platform section for [mode]; before the first frame is out only
     * Backend shows, without its platform sections. The danger zone shows when there is a token to
     * remove or an identity to reset.
     */
    internal fun visibleGroups(
        mode: LettaConfig.Mode,
        afterFirstFrame: Boolean,
        hasSavedToken: Boolean = false,
    ): List<Pair<SettingsGroup, List<SettingsContribution>>> {
        val forMode = contributions.filter { it.showForMode == null || it.showForMode == mode }
        val groups = if (afterFirstFrame) SettingsGroup.entries else listOf(SettingsGroup.Backend)
        return groups
            .map { group -> group to forMode.filter { it.group == group }.takeIf { afterFirstFrame }.orEmpty() }
            .filter { (group, groupContributions) -> hasStandardSection(group, hasSavedToken) || groupContributions.isNotEmpty() }
    }

    /**
     * The page's groups for this frame: on the first one only Backend is drawn (without its platform
     * sections) and the groups that will follow are fixed-height placeholders, so nothing jumps.
     */
    internal fun pageGroups(mode: LettaConfig.Mode, afterFirstFrame: Boolean, hasSavedToken: Boolean): PageGroups {
        val all = visibleGroups(mode, afterFirstFrame = true, hasSavedToken = hasSavedToken)
        val first = if (afterFirstFrame) all else visibleGroups(mode, afterFirstFrame = false, hasSavedToken = hasSavedToken)
        val rest = all.filter { (group, _) -> first.none { it.first == group } }
        return PageGroups(first + rest, first.size)
    }

    /** Whether [group] has a standard section here (the Backend one always does). */
    private fun hasStandardSection(group: SettingsGroup, hasSavedToken: Boolean): Boolean = when (group) {
        SettingsGroup.Backend -> true
        SettingsGroup.Appearance -> true
        SettingsGroup.Chat -> chat?.isEmpty == false
        SettingsGroup.Notifications -> notifications?.attention != null
        SettingsGroup.Providers -> onOpenProviders != null
        SettingsGroup.Danger -> hasSavedToken || backend.onIrohIdentityReset != null
        SettingsGroup.About, SettingsGroup.Developer -> false
    }
}

/** Every group of the page in order, and how many of them (from the top) are drawn now; the rest are placeholders. */
internal class PageGroups(val groups: List<Pair<SettingsGroup, List<SettingsContribution>>>, val drawn: Int) {
    /** The groups drawn as placeholders this frame. */
    val skeletons: List<SettingsGroup> get() = groups.drop(drawn).map { it.first }
}

object SettingsPageTags {
    const val PAGE = "settings-page"
    const val PAIRING = "settings-pairing"
    const val GROUP_PREFIX = "settings-group-"
    const val SKELETON_PREFIX = "settings-skeleton-"
    const val SEARCH = "settings-search"
    const val SEARCH_EMPTY = "settings-search-empty"
    const val SEARCH_RESULTS = "settings-search-results"
    const val SEARCH_HIT_PREFIX = "settings-search-hit-"
    const val RAIL = "settings-rail"
    const val RAIL_PREFIX = "settings-rail-"
    const val CHIPS = "settings-chips"
    const val CHIP_PREFIX = "settings-chip-"
    const val CONTENT = "settings-content"
}

/**
 *  (hard cut S10) / the one Settings page of every client
 * (Android, desktop, web), drawn through ShellPage's Settings destination. Groups in page order:
 * Backend (mode, status, address, test connection, pairing, the platform's runtime sections), Chat,
 * Appearance, Notifications, Providers, Diagnostics & about, Developer, and the Danger zone last.
 * The layout follows the width ([SettingsLayout]); the search filters rows across every section. The
 * page owns its insets through [LettaPageScaffold] (edge to edge on Android); [SettingsPageState.section]
 * scrolls to its anchor on open.
 */
@Composable
fun SettingsPage(
    state: SettingsPageState,
    services: SettingsPageServices,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
) {
    LettaPageScaffold(
        modifier = modifier.testTag(SettingsPageTags.PAGE),
        containerColor = MaterialTheme.colorScheme.background,
        header = {
            ShellHeader(
                title = HeaderTitle.Text("Settings"),
                controls = HeaderControls(nav = onBack?.let { HeaderNav.Back(it) } ?: HeaderNav.None),
            )
        },
    ) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets)) {
            rememberSettingsPageModel(state, services).Body(SettingsLayout.of(maxWidth))
        }
    }
}

/** Where the page's deep-link anchors open. */
internal object SettingsAnchors {
    /** The group an anchor opens on. */
    fun groupOf(section: ShellSettingsSection): SettingsGroup = when (section) {
        ShellSettingsSection.Pairing -> SettingsGroup.Backend
        ShellSettingsSection.About -> SettingsGroup.About
    }

    /** Whether [section] scrolls [group] into view; Pairing anchors its own card inside Backend instead. */
    fun scrollsTo(section: ShellSettingsSection?, group: SettingsGroup): Boolean =
        section != null && section != ShellSettingsSection.Pairing && groupOf(section) == group
}

/** The group an anchor opens on. */
val ShellSettingsSection.group: SettingsGroup
    get() = SettingsAnchors.groupOf(this)
