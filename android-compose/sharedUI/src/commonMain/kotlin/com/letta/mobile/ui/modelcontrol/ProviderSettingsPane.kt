package com.letta.mobile.ui.modelcontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.letta.mobile.data.repository.modelcontrol.ConnectableProvider
import com.letta.mobile.data.repository.modelcontrol.ProviderConnectMethod
import com.letta.mobile.data.repository.modelcontrol.ProviderManagementController
import com.letta.mobile.data.repository.modelcontrol.ProviderManagementState
import com.letta.mobile.data.repository.modelcontrol.connectMethod
import com.letta.mobile.data.repository.modelcontrol.settingsLists
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

/** The sub-pages of Providers, in navigation order. */
enum class ProviderSettingsPage(val label: String) {
    ACCOUNTS(ModelControlStrings.PAGE_ACCOUNTS),
    API_KEYS(ModelControlStrings.PAGE_API_KEYS),
    ENDPOINTS(ModelControlStrings.PAGE_ENDPOINTS),
    MODELS(ModelControlStrings.PAGE_MODELS),
}

/** Callbacks of [ProviderSettingsPane]: the management ones plus the terminal sign-in and endpoint edit. */
data class ProviderSettingsActions(
    val management: ProviderManagementActions,
    val onOpenSignIn: (ConnectableProvider) -> Unit,
    val onCheckSignIn: () -> Unit,
    val onDismissSignIn: () -> Unit,
    val onEdit: (ConnectableProvider) -> Unit,
) {
    companion object {
        fun bind(controller: ProviderManagementController) = ProviderSettingsActions(
            management = ProviderManagementActions.bind(controller),
            onOpenSignIn = controller::openSignIn,
            onCheckSignIn = controller::checkSignIn,
            onDismissSignIn = controller::dismissSignIn,
            onEdit = controller::openEdit,
        )
    }
}

object ProviderSettingsTags {
    const val PANE = "provider_settings"
    const val NAV_PREFIX = "provider_settings_nav_"
    const val REFRESH = "provider_settings_refresh"
    const val CARD_PREFIX = "provider_card_"
    const val REMOVE_PREFIX = "provider_remove_"
    const val ADD_PREFIX = "provider_add_"
    const val EDIT_PREFIX = "provider_edit_"
    const val HAVE_KEY = "provider_have_api_key"
    const val INLINE_FORM = "provider_inline_form"
    const val SIGN_IN = "provider_sign_in"
    const val CHECK_AGAIN = "provider_check_again"
}

/**
 * Providers settings (letta-mobile-w4q4p.6.1), modelled on Hermes' Settings ->
 * Providers: a left navigation (Accounts, API keys, Custom Endpoints, Models)
 * on wide windows, tabs on a phone. Everything comes from one
 * [ProviderManagementState]; the desktop destination and the Android screen
 * only host it.
 */
@Composable
fun ProviderSettingsPane(
    state: ProviderManagementState,
    actions: ProviderSettingsActions,
    page: ProviderSettingsPage,
    onPageChange: (ProviderSettingsPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize().testTag(ProviderSettingsTags.PANE)) {
        if (maxWidth >= LettaDimens.Pane.wideBreakpoint) {
            Row(modifier = Modifier.fillMaxSize()) {
                SettingsNav(page, onPageChange, modifier = Modifier.width(LettaDimens.Pane.navWidth).fillMaxHeight())
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                PageColumn(state, actions, page, onPageChange, modifier = Modifier.weight(1f))
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                PrimaryScrollableTabRow(selectedTabIndex = page.ordinal, edgePadding = LettaDimens.Space.lg) {
                    ProviderSettingsPage.entries.forEach { entry ->
                        Tab(
                            selected = entry == page,
                            onClick = { onPageChange(entry) },
                            text = { Text(entry.label) },
                            modifier = Modifier.testTag("${ProviderSettingsTags.NAV_PREFIX}${entry.name.lowercase()}"),
                        )
                    }
                }
                PageColumn(state, actions, page, onPageChange, modifier = Modifier.weight(1f))
            }
        }
    }
    PaneDialogs(state, actions, page)
}

@Composable
private fun SettingsNav(page: ProviderSettingsPage, onPageChange: (ProviderSettingsPage) -> Unit, modifier: Modifier) {
    Column(
        modifier = modifier.padding(vertical = LettaDimens.Space.md, horizontal = LettaDimens.Space.sm).selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
    ) {
        Text(
            text = ModelControlStrings.PROVIDERS,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.sm).semantics { heading() },
        )
        ProviderSettingsPage.entries.forEach { entry ->
            val selected = entry == page
            Surface(
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = selected, role = Role.Tab, onClick = { onPageChange(entry) })
                    .testTag("${ProviderSettingsTags.NAV_PREFIX}${entry.name.lowercase()}"),
            ) {
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.sm),
                )
            }
        }
    }
}

@Composable
private fun PageColumn(
    state: ProviderManagementState,
    actions: ProviderSettingsActions,
    page: ProviderSettingsPage,
    onPageChange: (ProviderSettingsPage) -> Unit,
    modifier: Modifier,
) {
    Column(modifier = modifier.fillMaxHeight()) {
        if (page == ProviderSettingsPage.MODELS) {
            // The full Providers & Models pane already has its own toolbar, progress and dialogs.
            ProviderManagementPane(state = state, actions = actions.management)
            return@Column
        }
        PageHeader(page, state, actions.management.onRefresh)
        if (state.loading || state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (state.signIn == null) ModelControlNotice(error = state.error.takeIf { state.form == null }, message = state.message)
        val lists = state.settingsLists
        // widthIn before fillMaxSize: the cap has to narrow the constraints before they are filled.
        val pageModifier = Modifier.widthIn(max = LettaDimens.Pane.contentMaxWidth).fillMaxSize()
        when (page) {
            ProviderSettingsPage.ACCOUNTS -> AccountsPage(lists, state, actions, onPageChange, pageModifier)
            ProviderSettingsPage.API_KEYS -> CredentialsPage(CredentialsKind.API_KEYS, lists.apiKeys, state, actions, pageModifier)
            ProviderSettingsPage.ENDPOINTS -> CredentialsPage(CredentialsKind.ENDPOINTS, lists.endpoints, state, actions, pageModifier)
            ProviderSettingsPage.MODELS -> Unit
        }
    }
}

@Composable
private fun PageHeader(page: ProviderSettingsPage, state: ProviderManagementState, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = LettaDimens.Space.lg, end = LettaDimens.Space.sm, top = LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = page.label,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        IconButton(
            onClick = onRefresh,
            enabled = !state.loading,
            modifier = Modifier.testTag(ProviderSettingsTags.REFRESH),
        ) { Icon(LettaIcons.Refresh, contentDescription = ModelControlStrings.REFRESH_PROVIDERS) }
    }
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        modifier = Modifier.padding(top = LettaDimens.Space.xs),
    )
}

/**
 * The pane-level dialogs: the terminal sign-in steps, a connect form that is
 * not shown inline on the current page, and the remove confirmation. The
 * Models page's own pane renders its dialogs itself.
 */
@Composable
private fun PaneDialogs(state: ProviderManagementState, actions: ProviderSettingsActions, page: ProviderSettingsPage) {
    if (page == ProviderSettingsPage.MODELS) return
    state.signIn?.let { provider ->
        TerminalSignInDialog(
            provider = provider,
            state = state,
            onCheck = actions.onCheckSignIn,
            onDismiss = actions.onDismissSignIn,
        )
    }
    state.form?.let { form ->
        if (!showsInline(page, form.provider)) ProviderConnectDialog(form, busy = state.busy, actions = actions.management.form)
    }
    state.pendingDisconnect?.let { provider ->
        DisconnectConfirmDialog(
            provider = provider,
            onConfirm = actions.management.onConfirmDisconnect,
            onDismiss = actions.management.onDismissDisconnect,
        )
    }
}

/** The API keys and Custom Endpoints pages edit their own providers in place, not in a dialog. */
internal fun showsInline(page: ProviderSettingsPage, provider: ConnectableProvider): Boolean = when (page) {
    ProviderSettingsPage.API_KEYS -> provider.connectMethod == ProviderConnectMethod.API_KEY
    ProviderSettingsPage.ENDPOINTS -> provider.connectMethod == ProviderConnectMethod.ENDPOINT
    else -> false
}
