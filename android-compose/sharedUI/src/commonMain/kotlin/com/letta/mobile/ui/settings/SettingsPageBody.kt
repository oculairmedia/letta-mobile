package com.letta.mobile.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import com.letta.mobile.data.settings.SettingsContribution
import com.letta.mobile.data.settings.SettingsGroup
import com.letta.mobile.data.shell.ShellSettingsSection
import com.letta.mobile.ui.pairing.InstallAndPairCard
import com.letta.mobile.ui.theme.LettaDimens
import kotlin.math.roundToInt

// letta-mobile-ckmub.3.12.1: the Settings page's body for one frame: the layout for the width,
// the scrolling column of groups (or of search hits), the reader's place for the rail and chips.

/** What one frame of the page draws: the groups, the reader's place in them, and the search. */
@OptIn(ExperimentalFoundationApi::class)
internal class SettingsPageModel(
    private val inputs: StandardSectionInputs,
    /** Every group the page has, in order; before the first frame is out only Backend is drawn. */
    private val groups: PageGroups,
    private val scroll: ScrollState,
    private val anchors: SnapshotStateMap<SettingsGroup, Int>,
    private val search: PageSearch,
    private val anchor: BringIntoViewRequester,
) {
    private val groupList: List<SettingsGroup> get() = groups.groups.map { it.first }

    /** The group in view, for the rail's and the chips' selection. */
    private fun active(slop: Int): SettingsGroup? {
        val tops = groupList.mapNotNull { group -> anchors[group]?.let { group to it } }
        return settingsScrollSpy(tops, scroll.value, scroll.value >= scroll.maxValue, slop)
    }

    @Composable
    fun Body(layout: SettingsLayout) {
        val active = active(with(LocalDensity.current) { LettaDimens.Space.xxl.roundToPx() })
        when (layout) {
            SettingsLayout.Phone -> Content(layout, Modifier.fillMaxSize()) { SettingsSearchField(search.query, search.onQuery) }
            SettingsLayout.Medium -> MediumBody(active)
            SettingsLayout.Wide -> WideBody(active)
        }
    }

    @Composable
    private fun MediumBody(active: SettingsGroup?) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier.widthIn(max = LettaDimens.Pane.settingsColumnMaxWidth).padding(horizontal = LettaDimens.Space.xl, vertical = LettaDimens.Space.sm),
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
            ) {
                SettingsSearchField(search.query, search.onQuery)
                SettingsChips(groupList, active, search.onOpen)
            }
            Content(SettingsLayout.Medium, Modifier.weight(1f).fillMaxWidth())
        }
    }

    @Composable
    private fun WideBody(active: SettingsGroup?) {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier.width(LettaDimens.Pane.navWidth).fillMaxHeight().padding(LettaDimens.Space.lg),
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
            ) {
                SettingsSearchField(search.query, search.onQuery)
                SettingsRail(groupList, active, search.onOpen)
            }
            Content(SettingsLayout.Wide, Modifier.weight(1f).fillMaxHeight())
        }
    }

    /** The scrolling column of groups (or of search hits), capped at the layout's readable width. */
    @Composable
    private fun Content(layout: SettingsLayout, modifier: Modifier, top: @Composable () -> Unit = {}) {
        Box(modifier.verticalScroll(scroll).testTag(SettingsPageTags.CONTENT), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = layout.columnMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = layout.gutter, vertical = LettaDimens.Space.lg),
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg),
            ) {
                top()
                val query = search.query.takeIf { it.isNotBlank() }
                query?.let { SettingsSearchResults(inputs.searchHits(it), it, search.onOpen) } ?: Groups()
            }
        }
    }

    @Composable
    private fun Groups() {
        groups.groups.forEachIndexed { index, (group, contributions) ->
            val place = Modifier.onGloballyPositioned { anchors[group] = it.positionInParent().y.roundToInt() }
            when {
                index < groups.drawn -> inputs.Block(group, GroupExtras(contributions, anchor), place)
                else -> Box(place) { SettingsSkeletonGroup(group) }
            }
        }
    }
}

/** The page's query, and what typing and opening a hit (or a rail tab) do. */
internal class PageSearch(val query: String, val onQuery: (String) -> Unit, val onOpen: (SettingsGroup) -> Unit)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun rememberSettingsPageModel(state: SettingsPageState, services: SettingsPageServices): SettingsPageModel {
    var form by remember(state.config) { mutableStateOf(BackendSettingsForm.of(state.config)) }
    var query by rememberSaveable { mutableStateOf("") }
    var pending by remember { mutableStateOf<SettingsGroup?>(null) }
    val scroll = rememberScrollState()
    val anchors = remember { mutableStateMapOf<SettingsGroup, Int>() }
    val anchor = remember { BringIntoViewRequester() }
    val afterFirstFrame = rememberAfterFirstFrame()
    LaunchedEffect(state.section, afterFirstFrame) { state.section?.takeIf { afterFirstFrame }?.let { anchor.bringIntoView() } }
    val target = pending?.let { anchors[it] }
    LaunchedEffect(pending, target) {
        target?.let {
            scroll.animateScrollTo(it)
            pending = null
        }
    }
    val backend = BackendFormState(state.config, form, state.connection) { form = it }
    val search = PageSearch(query, onQuery = { query = it }, onOpen = { group ->
        query = ""
        pending = group
    })
    return SettingsPageModel(
        inputs = StandardSectionInputs(state, services, backend, afterFirstFrame),
        groups = services.pageGroups(form.mode, afterFirstFrame, state.config.accessToken != null),
        scroll = scroll,
        anchors = anchors,
        search = search,
        anchor = anchor,
    )
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

/** A group's platform sections and the page's anchor. */
@OptIn(ExperimentalFoundationApi::class)
internal class GroupExtras(val contributions: List<SettingsContribution>, val anchor: BringIntoViewRequester)

/** What the standard sections read, and how a group draws them. */
@OptIn(ExperimentalFoundationApi::class)
internal class StandardSectionInputs(
    private val state: SettingsPageState,
    private val services: SettingsPageServices,
    private val backend: BackendFormState,
    /** False on the page's first frame, which draws the Backend form alone. */
    private val afterFirstFrame: Boolean,
) {
    /** Why rows that need a host are locked now: no connected host is known. Null while one is. */
    private val hostLock: String?
        get() = SETTINGS_NEEDS_HOST.takeIf { state.connection.let { it != null && it !is BackendConnectionStatus.Connected } }

    /** The search hits for [query] across every section the page would draw. */
    fun searchHits(query: String): List<SettingsSearchEntry> =
        services.searchEntries(backend.form.mode, state.config.accessToken != null).matching(query)

    /** [anchor] on this modifier when [anchored], so the page can scroll it into view. */
    private fun Modifier.anchoredTo(anchor: BringIntoViewRequester, anchored: Boolean): Modifier =
        then(Modifier.bringIntoViewRequester(anchor).takeIf { anchored } ?: Modifier)

    /**
     * One group: its header, its standard section, then the platform's own sections; the anchored
     * group scrolls into view. While no host is connected, the sections that need one are locked.
     */
    @Composable
    fun Block(group: SettingsGroup, extras: GroupExtras, modifier: Modifier) {
        Column(
            modifier = modifier
                .anchoredTo(extras.anchor, SettingsAnchors.scrollsTo(state.section, group))
                .testTag(SettingsPageTags.GROUP_PREFIX + group.name),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        ) {
            SettingsGroupHeader(group)
            Section(group, extras.anchor)
            extras.contributions.forEach { contribution ->
                CompositionLocalProvider(LocalSettingsLock provides hostLock?.takeIf { contribution.needsHost }) {
                    SettingsContributionSection(contribution)
                }
            }
        }
    }

    @Composable
    private fun Section(group: SettingsGroup, anchor: BringIntoViewRequester) {
        when (group) {
            SettingsGroup.Backend -> BackendGroup(anchor)
            SettingsGroup.Appearance -> services.appearance?.let { AppearanceSettingsSection(it) }
            SettingsGroup.Chat -> services.chat?.let { ChatSettingsSection(it) }
            SettingsGroup.Notifications -> services.notifications?.let { NotificationSettingsSection(it) }
            SettingsGroup.Providers -> services.onOpenProviders?.let { open ->
                CompositionLocalProvider(LocalSettingsLock provides hostLock) { ProvidersSettingsSection(open) }
            }
            SettingsGroup.Danger -> DangerZoneSection(backend, services.backend)
            SettingsGroup.About, SettingsGroup.Developer -> Unit
        }
    }

    /** The Backend form, then (after the first frame) the pairing card, which the Pairing anchor scrolls to. */
    @Composable
    private fun BackendGroup(anchor: BringIntoViewRequester) {
        BackendSettingsSection(backend, services.backend)
        services.pairing?.takeIf { afterFirstFrame }?.let { pairing ->
            InstallAndPairCard(
                pairing,
                Modifier.testTag(SettingsPageTags.PAIRING).anchoredTo(anchor, state.section == ShellSettingsSection.Pairing),
            )
        }
    }
}
