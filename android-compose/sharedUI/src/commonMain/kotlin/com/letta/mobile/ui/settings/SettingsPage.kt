package com.letta.mobile.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
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
import com.letta.mobile.ui.pairing.InstallAndPairCard
import com.letta.mobile.ui.shell.header.HeaderControls
import com.letta.mobile.ui.shell.header.HeaderNav
import com.letta.mobile.ui.shell.header.HeaderTitle
import com.letta.mobile.ui.shell.header.ShellHeader
import com.letta.mobile.ui.shell.scaffold.LettaPageScaffold
import com.letta.mobile.ui.theme.LettaDimens
import kotlin.math.roundToInt

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
)

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
 * letta-mobile-ckmub.3.12 (hard cut S10) / ckmub.3.12.1: the one Settings page of every client
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
            SettingsBody(state, services, SettingsLayout.of(maxWidth))
        }
    }
}

/** What one frame of the page draws: the groups, the reader's place in them, and the search. */
private class SettingsPageModel(
    val inputs: StandardSectionInputs,
    /** Every group the page has, in order; before the first frame is out only Backend is drawn. */
    val groups: List<Pair<SettingsGroup, List<SettingsContribution>>>,
    val drawn: Int,
    val scroll: ScrollState,
    val anchors: SnapshotStateMap<SettingsGroup, Int>,
    val query: String,
    val onQuery: (String) -> Unit,
    val onOpen: (SettingsGroup) -> Unit,
    val anchor: BringIntoViewRequester,
) {
    val groupList: List<SettingsGroup> get() = groups.map { it.first }
}

@Composable
private fun SettingsBody(state: SettingsPageState, services: SettingsPageServices, layout: SettingsLayout) {
    val model = rememberSettingsPageModel(state, services)
    val spySlop = with(LocalDensity.current) { LettaDimens.Space.xxl.roundToPx() }
    val anchors = model.groupList.mapNotNull { group -> model.anchors[group]?.let { group to it } }
    val active = settingsScrollSpy(anchors, model.scroll.value, model.scroll.value >= model.scroll.maxValue, spySlop)
    when (layout) {
        SettingsLayout.Phone -> SettingsColumn(model, layout, Modifier.fillMaxSize()) {
            SettingsSearchField(model.query, model.onQuery)
        }
        SettingsLayout.Medium -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier.widthIn(max = LettaDimens.Pane.settingsColumnMaxWidth).padding(horizontal = LettaDimens.Space.xl, vertical = LettaDimens.Space.sm),
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
            ) {
                SettingsSearchField(model.query, model.onQuery)
                SettingsChips(model.groupList, active, model.onOpen)
            }
            SettingsColumn(model, layout, Modifier.weight(1f).fillMaxWidth())
        }
        SettingsLayout.Wide -> Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(LettaDimens.Pane.navWidth).fillMaxHeight().padding(LettaDimens.Space.lg),
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
            ) {
                SettingsSearchField(model.query, model.onQuery)
                SettingsRail(model.groupList, active, model.onOpen)
            }
            SettingsColumn(model, layout, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberSettingsPageModel(state: SettingsPageState, services: SettingsPageServices): SettingsPageModel {
    var form by remember(state.config) { mutableStateOf(BackendSettingsForm.of(state.config)) }
    var query by rememberSaveable { mutableStateOf("") }
    var pending by remember { mutableStateOf<SettingsGroup?>(null) }
    val scroll = rememberScrollState()
    val anchors = remember { mutableStateMapOf<SettingsGroup, Int>() }
    val anchor = remember { BringIntoViewRequester() }
    val afterFirstFrame = rememberAfterFirstFrame()
    val hasToken = state.config.accessToken != null
    LaunchedEffect(state.section, afterFirstFrame) { if (state.section != null && afterFirstFrame) anchor.bringIntoView() }
    val target = pending?.let { anchors[it] }
    LaunchedEffect(pending, target) {
        if (target != null) {
            scroll.animateScrollTo(target)
            pending = null
        }
    }
    val backend = BackendFormState(state.config, form, state.connection) { form = it }
    val groups = services.pageGroups(form.mode, afterFirstFrame, hasToken)
    return SettingsPageModel(
        inputs = StandardSectionInputs(state, services, backend, afterFirstFrame),
        groups = groups.groups,
        drawn = groups.drawn,
        scroll = scroll,
        anchors = anchors,
        query = query,
        onQuery = { query = it },
        onOpen = { group ->
            query = ""
            pending = group
        },
        anchor = anchor,
    )
}

/** The scrolling column of groups (or of search hits), capped at a readable width. */
@Composable
private fun SettingsColumn(
    model: SettingsPageModel,
    layout: SettingsLayout,
    modifier: Modifier,
    top: (@Composable () -> Unit)? = null,
) {
    val gutter = if (layout == SettingsLayout.Phone) LettaDimens.Space.lg else LettaDimens.Space.xl
    Box(modifier.verticalScroll(model.scroll).testTag(SettingsPageTags.CONTENT), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .then(if (layout == SettingsLayout.Phone) Modifier else Modifier.widthIn(max = LettaDimens.Pane.settingsColumnMaxWidth))
                .fillMaxWidth()
                .padding(horizontal = gutter, vertical = LettaDimens.Space.lg),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg),
        ) {
            top?.invoke()
            if (model.query.isNotBlank()) {
                val entries = model.inputs.services.searchEntries(model.inputs.backend.form.mode, model.inputs.state.config.accessToken != null)
                SettingsSearchResults(entries.matching(model.query), model.query, model.onOpen)
            } else {
                SettingsGroups(model)
            }
        }
    }
}

@Composable
private fun SettingsGroups(model: SettingsPageModel) {
    model.groups.forEachIndexed { index, (group, contributions) ->
        val place = Modifier.onGloballyPositioned { model.anchors[group] = it.positionInParent().y.roundToInt() }
        if (index < model.drawn) {
            SettingsGroupBlock(group, model.inputs, GroupExtras(contributions, model.anchor), place)
        } else {
            Box(place) { SettingsSkeletonGroup(group) }
        }
    }
}

/**
 * False for the page's first frame, true from the next one. The first frame draws only the Backend
 * form (the first screen of an unconfigured install) and fixed-height placeholders for the other
 * groups; every other section, the pairing card and its QR included, joins on the next frame.
 */
@Composable
private fun rememberAfterFirstFrame(): Boolean {
    val after by produceState(false) {
        withFrameNanos { }
        value = true
    }
    return after
}

/**
 * The groups the page draws now, each with the platform sections it holds: a group shows when it has
 * a standard section or a platform section for [mode]; before the first frame is out only Backend
 * shows, without its platform sections. The danger zone shows when there is a token to remove or an
 * identity to reset.
 */
internal fun SettingsPageServices.visibleGroups(
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

/** Every group of the page in order, and how many of them (from the top) are drawn now; the rest are placeholders. */
internal class PageGroups(val groups: List<Pair<SettingsGroup, List<SettingsContribution>>>, val drawn: Int) {
    /** The groups drawn as placeholders this frame. */
    val skeletons: List<SettingsGroup> get() = groups.drop(drawn).map { it.first }
}

/**
 * The page's groups for this frame: on the first one only Backend is drawn (without its platform
 * sections) and the groups that will follow are fixed-height placeholders, so nothing jumps.
 */
internal fun SettingsPageServices.pageGroups(mode: LettaConfig.Mode, afterFirstFrame: Boolean, hasSavedToken: Boolean): PageGroups {
    val all = visibleGroups(mode, afterFirstFrame = true, hasSavedToken = hasSavedToken)
    if (afterFirstFrame) return PageGroups(all, all.size)
    val first = visibleGroups(mode, afterFirstFrame = false, hasSavedToken = hasSavedToken)
    val rest = all.filter { (group, _) -> first.none { it.first == group } }
    return PageGroups(first + rest, first.size)
}

/** A group's platform sections and the page's anchor. */
private class GroupExtras(val contributions: List<SettingsContribution>, val anchor: BringIntoViewRequester)

/**
 * One group: its header, its standard section, then the platform's own sections; the anchored group
 * scrolls into view. While no host is connected, the sections that need one are locked with the reason.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SettingsGroupBlock(group: SettingsGroup, inputs: StandardSectionInputs, extras: GroupExtras, modifier: Modifier) {
    val anchored = inputs.state.section?.anchorsGroup(group) == true
    Column(
        modifier = modifier
            .then(if (anchored) Modifier.bringIntoViewRequester(extras.anchor) else Modifier)
            .testTag(SettingsPageTags.GROUP_PREFIX + group.name),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        SettingsGroupHeader(group)
        StandardSection(group, inputs, extras.anchor)
        extras.contributions.forEach { contribution ->
            val lock = SETTINGS_NEEDS_HOST.takeIf { contribution.needsHost && inputs.offline }
            CompositionLocalProvider(LocalSettingsLock provides lock) { SettingsContributionSection(contribution) }
        }
    }
}

/** What the standard section of a group reads. */
private class StandardSectionInputs(
    val state: SettingsPageState,
    val services: SettingsPageServices,
    val backend: BackendFormState,
    /** False on the page's first frame, which draws the Backend form alone. */
    val afterFirstFrame: Boolean,
) {
    /** True while the page knows of no connected host: the rows that need one are locked. */
    val offline: Boolean get() = state.connection.let { it != null && it !is BackendConnectionStatus.Connected }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StandardSection(group: SettingsGroup, inputs: StandardSectionInputs, anchor: BringIntoViewRequester) {
    val services = inputs.services
    when (group) {
        SettingsGroup.Backend -> {
            BackendSettingsSection(inputs.backend, services.backend)
            services.pairing?.takeIf { inputs.afterFirstFrame }?.let { pairing ->
                val anchored = inputs.state.section == ShellSettingsSection.Pairing
                InstallAndPairCard(
                    pairing,
                    Modifier.testTag(SettingsPageTags.PAIRING).then(if (anchored) Modifier.bringIntoViewRequester(anchor) else Modifier),
                )
            }
        }
        SettingsGroup.Appearance -> services.appearance?.let { AppearanceSettingsSection(it) }
        SettingsGroup.Chat -> services.chat?.let { ChatSettingsSection(it) }
        SettingsGroup.Notifications -> services.notifications?.let { NotificationSettingsSection(it) }
        SettingsGroup.Providers -> services.onOpenProviders?.let { open ->
            val lock = SETTINGS_NEEDS_HOST.takeIf { inputs.offline }
            CompositionLocalProvider(LocalSettingsLock provides lock) { ProvidersSettingsSection(open) }
        }
        SettingsGroup.Danger -> DangerZoneSection(inputs.backend, services.backend)
        SettingsGroup.About, SettingsGroup.Developer -> Unit
    }
}

/** Whether [group] has a standard section here (the Backend one always does). */
private fun SettingsPageServices.hasStandardSection(group: SettingsGroup, hasSavedToken: Boolean): Boolean = when (group) {
    SettingsGroup.Backend -> true
    SettingsGroup.Appearance -> appearance?.isEmpty == false
    SettingsGroup.Chat -> chat?.isEmpty == false
    SettingsGroup.Notifications -> notifications?.attention != null
    SettingsGroup.Providers -> onOpenProviders != null
    SettingsGroup.Danger -> hasSavedToken || backend.onIrohIdentityReset != null
    SettingsGroup.About, SettingsGroup.Developer -> false
}

/** Whether this anchor scrolls [group] into view; Pairing anchors its own card inside Backend instead. */
private fun ShellSettingsSection.anchorsGroup(group: SettingsGroup): Boolean =
    this != ShellSettingsSection.Pairing && this.group == group

/** The group an anchor opens on. */
val ShellSettingsSection.group: SettingsGroup
    get() = when (this) {
        ShellSettingsSection.Pairing -> SettingsGroup.Backend
        ShellSettingsSection.About -> SettingsGroup.About
    }
