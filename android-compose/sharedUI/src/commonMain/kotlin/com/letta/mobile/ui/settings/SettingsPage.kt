package com.letta.mobile.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.letta.mobile.ui.pairing.InstallAndPairCard
import com.letta.mobile.ui.shell.header.HeaderControls
import com.letta.mobile.ui.shell.header.HeaderNav
import com.letta.mobile.ui.shell.header.HeaderTitle
import com.letta.mobile.ui.shell.header.ShellHeader
import com.letta.mobile.ui.shell.scaffold.LettaPageScaffold
import com.letta.mobile.ui.theme.LettaDimens

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
}

/**
 * letta-mobile-ckmub.3.12 (hard cut S10): the one Settings page of every client (Android, desktop,
 * web), drawn through ShellPage's Settings destination. Groups in page order: Backend (mode and
 * address, pairing, the platform's runtime sections), Appearance, Chat, Notifications, Providers,
 * Diagnostics & about, Developer. The page owns its insets through [LettaPageScaffold] (edge to edge
 * on Android) and keeps its content to a readable width; [SettingsPageState.section] scrolls to its
 * anchor on open.
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
            // The header's drag lane fills the height it is given; size it to its own content (a
            // title and a back arrow, nothing subcomposed) so it never takes the page.
            ShellHeader(
                title = HeaderTitle.Text("Settings"),
                modifier = Modifier.height(IntrinsicSize.Min),
                controls = HeaderControls(nav = onBack?.let { HeaderNav.Back(it) } ?: HeaderNav.None),
            )
        },
    ) { insets ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(insets)
                .padding(LettaDimens.Space.xl),
        ) {
            SettingsGroups(state, services)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ColumnScope.SettingsGroups(state: SettingsPageState, services: SettingsPageServices) {
    var form by remember(state.config) { mutableStateOf(BackendSettingsForm.of(state.config)) }
    val contributions = services.contributions.filter { it.showForMode == null || it.showForMode == form.mode }
    val anchor = remember { BringIntoViewRequester() }
    LaunchedEffect(state.section) { if (state.section != null) anchor.bringIntoView() }
    Column(
        modifier = Modifier.widthIn(max = LettaDimens.Pane.contentMaxWidth).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg),
    ) {
        state.connection?.let { connection ->
            BackendConnectionStatusRow(connection)
            (connection as? BackendConnectionStatus.Failed)?.let { BackendConnectionFailure(it.message) }
        }
        SettingsGroup.entries.forEach { group ->
            val groupContributions = contributions.filter { it.group == group }
            if (!services.hasStandardSection(group) && groupContributions.isEmpty()) return@forEach
            val anchored = state.section?.group == group
            Column(
                modifier = Modifier
                    .then(if (anchored && state.section != ShellSettingsSection.Pairing) Modifier.bringIntoViewRequester(anchor) else Modifier)
                    .testTag(SettingsPageTags.GROUP_PREFIX + group.name),
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg),
            ) {
                StandardSection(group, StandardSectionInputs(state, services, form) { form = it }, anchor)
                groupContributions.forEach { SettingsContributionSection(it) }
            }
        }
    }
}

/** What the standard section of a group reads. */
private class StandardSectionInputs(
    val state: SettingsPageState,
    val services: SettingsPageServices,
    val form: BackendSettingsForm,
    val onFormChange: (BackendSettingsForm) -> Unit,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StandardSection(group: SettingsGroup, inputs: StandardSectionInputs, anchor: BringIntoViewRequester) {
    val services = inputs.services
    when (group) {
        SettingsGroup.Backend -> {
            BackendSettingsSection(inputs.state.config, inputs.form, inputs.onFormChange, services.backend)
            services.pairing?.let { pairing ->
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
        SettingsGroup.Providers -> services.onOpenProviders?.let { ProvidersSettingsSection(it) }
        SettingsGroup.About, SettingsGroup.Developer -> Unit
    }
}

/** Whether [group] has a standard section here (the Backend one always does). */
private fun SettingsPageServices.hasStandardSection(group: SettingsGroup): Boolean = when (group) {
    SettingsGroup.Backend -> true
    SettingsGroup.Appearance -> appearance?.let { it.theme ?: it.themePreset ?: it.dynamicColor ?: it.haptics } != null
    SettingsGroup.Chat -> chat?.let { it.sharedChatPage ?: it.openChatsOnCanvas ?: it.permissionMode } != null
    SettingsGroup.Notifications -> notifications?.attention != null
    SettingsGroup.Providers -> onOpenProviders != null
    SettingsGroup.About, SettingsGroup.Developer -> false
}

@Composable
private fun BackendConnectionFailure(message: String) {
    Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

/** The group an anchor opens on. */
val ShellSettingsSection.group: SettingsGroup
    get() = when (this) {
        ShellSettingsSection.Pairing -> SettingsGroup.Backend
        ShellSettingsSection.About -> SettingsGroup.About
    }
