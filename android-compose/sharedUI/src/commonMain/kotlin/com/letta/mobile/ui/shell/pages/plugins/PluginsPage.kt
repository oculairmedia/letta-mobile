package com.letta.mobile.ui.shell.pages.plugins

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.plugin.catalog.PluginCatalog
import com.letta.mobile.data.plugin.catalog.PluginConsentItem
import com.letta.mobile.data.plugin.catalog.PluginId
import com.letta.mobile.data.plugin.catalog.PluginPackageFile
import com.letta.mobile.data.plugin.catalog.PluginsPageActions
import com.letta.mobile.data.plugin.catalog.PluginsPageController
import com.letta.mobile.data.plugin.catalog.PluginsPageState
import com.letta.mobile.data.settings.SettingValue
import com.letta.mobile.data.shell.ShellDestination
import com.letta.mobile.ui.components.list.RosterListTag
import com.letta.mobile.ui.shell.ShellPageContent
import com.letta.mobile.ui.shell.header.HeaderControls
import com.letta.mobile.ui.shell.header.HeaderNav
import com.letta.mobile.ui.shell.header.HeaderTitle
import com.letta.mobile.ui.shell.header.ShellHeader
import com.letta.mobile.ui.shell.scaffold.LettaPageScaffold
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.launch

/** Test tags of the Plugins page. */
object PluginsPageTags {
    const val PAGE = "plugins-page"
    const val ERROR = "plugins-page-error"
    const val LIVE_VIEWS = "plugins-page-live-views"
    const val PICK = "plugins-page-pick"
    const val PICKED = "plugins-page-picked"
    const val PICKED_SHA = "plugins-page-picked-sha"
    const val UPLOAD = "plugins-page-upload"
    const val DISCARD = "plugins-page-discard"
    const val DISABLE_ALL = "plugins-page-disable-all"
    const val DISABLE_ALL_CONFIRM = "plugins-page-disable-all-confirm"
    const val REMOVE_CONFIRM = "plugins-page-remove-confirm"
    const val CONSENT = "plugins-page-consent"
    const val CONSENT_INSTALL = "plugins-page-consent-install"
    const val CONSENT_CANCEL = "plugins-page-consent-cancel"
    val LIST = RosterListTag("plugins-page-list")

    fun toggle(id: PluginId) = "plugins-page-toggle-$id"
    fun remove(id: PluginId) = "plugins-page-remove-$id"
    fun health(id: PluginId) = "plugins-page-health-$id"
    fun fault(id: PluginId) = "plugins-page-fault-$id"
    fun allow(item: PluginConsentItem) = "plugins-page-allow-${item.key}"
    fun deny(item: PluginConsentItem) = "plugins-page-deny-${item.key}"
}

/**
 * What the host adds around the page: [onPickPackage] opens the platform's file picker (null hides
 * Install), and [onBack] gives the page its own header with a back arrow (Android's route; the
 * desktop shell draws its own title).
 */
@Immutable
data class PluginsPageOptions(
    val onPickPackage: (() -> Unit)? = null,
    val onBack: (() -> Unit)? = null,
)

/**
 * letta-mobile-cdgpr.9: the Plugins page shared by every client. The installed plugins with their
 * state, health and fault; install from a picked file (its sha256 shown before upload) through a
 * consent sheet that lists every capability, origin and page with an explicit Allow or Deny; enable,
 * disable and remove per plugin; "Disable all plugins" behind a confirmation; and this device's
 * "Live plugin views" switch. Data and calls come from sharedLogic's [PluginsPageController].
 */
@Composable
fun PluginsPage(
    state: PluginsPageState,
    actions: PluginsPageActions,
    modifier: Modifier = Modifier,
    options: PluginsPageOptions = PluginsPageOptions(),
) {
    val onBack = options.onBack
    if (onBack == null) {
        PluginsPageBody(state, actions, options.onPickPackage, modifier)
        return
    }
    LettaPageScaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        header = { ShellHeader(title = HeaderTitle.Text(TITLE), controls = HeaderControls(nav = HeaderNav.Back(onBack))) },
    ) { insets -> PluginsPageBody(state, actions, options.onPickPackage, Modifier.padding(insets)) }
}

@Composable
private fun PluginsPageBody(state: PluginsPageState, actions: PluginsPageActions, onPickPackage: (() -> Unit)?, modifier: Modifier) {
    Column(modifier.fillMaxSize().testTag(PluginsPageTags.PAGE).padding(horizontal = LettaDimens.Space.md)) {
        Text(
            INTRO,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = LettaDimens.Space.sm),
        )
        state.error?.let { PluginsError(it, actions::dismissError) }
        LiveViewsRow(state.liveViews, actions::setLiveViews)
        InstallSection(state, actions, onPickPackage)
        PluginsList(state, actions, Modifier.fillMaxWidth().weight(1f))
        DisableAllButton(state, actions)
    }
    PluginsPageDialogs(state, actions)
}

@Composable
private fun PluginsPageDialogs(state: PluginsPageState, actions: PluginsPageActions) {
    state.consent?.let { PluginConsentPane(it, state.busy, actions) }
    if (state.confirmDisableAll) DisableAllConfirm(actions)
    state.pendingRemove?.let { id -> RemoveConfirm(state.plugins.firstOrNull { it.id == id }?.name ?: id.value, actions) }
}

/**
 * What a host binds the page to: its [catalog], how it picks a package file ([pickPackage] opens the
 * platform's picker and answers the chosen file, or null when the owner cancelled; a null picker
 * hides Install), and this device's [liveViews] switch (null hides it).
 */
class PluginsPageBinding(
    val catalog: PluginCatalog,
    val pickPackage: (suspend () -> PluginPackageFile?)? = null,
    val liveViews: SettingValue<Boolean>? = null,
)

/** The shared Plugins page over [binding]'s catalog; the controller lives as long as the page is on screen. */
fun shellPluginsPage(binding: PluginsPageBinding, onBack: (() -> Unit)? = null) =
    ShellPageContent<ShellDestination.Plugins> { _, modifier ->
        val scope = rememberCoroutineScope()
        val controller = remember(binding.catalog) { PluginsPageController(binding.catalog, scope, binding.liveViews) }
        val state by controller.state.collectAsState()
        val onPick = binding.pickPackage?.let { pick -> { scope.launch { pick()?.let(controller::pick) }; Unit } }
        PluginsPage(state, controller, modifier, PluginsPageOptions(onPickPackage = onPick, onBack = onBack))
    }

private const val TITLE = "Plugins"
private const val INTRO = "Plugins add element kinds, agent tools and pages. Each one runs only with what you allowed when you installed it."
