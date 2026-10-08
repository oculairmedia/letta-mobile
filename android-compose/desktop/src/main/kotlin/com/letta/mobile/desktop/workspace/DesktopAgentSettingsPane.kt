package com.letta.mobile.desktop.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.letta.mobile.data.secrets.AgentSecretsFeature
import com.letta.mobile.data.secrets.AgentVaultController
import com.letta.mobile.ui.shell.pages.vault.AgentVaultPage
import com.letta.mobile.ui.shell.pages.vault.AgentVaultPageOptions
import com.letta.mobile.ui.theme.LettaDimens
import kotlinx.coroutines.CoroutineScope

private enum class AgentSettingsView(val label: String) { Profile("Profile"), Secrets("Secrets") }

/**
 * The agent editor pane: the agent's [profile] and, where the vault is enabled, its secrets
 * (letta-mobile-bzvro.25). The vault's values live only while the Secrets view is open.
 */
@Composable
internal fun DesktopAgentSettingsPane(
    agentId: String,
    scope: CoroutineScope,
    feature: AgentSecretsFeature = AgentSecretsFeature.Desktop,
    profile: @Composable (Modifier) -> Unit,
) {
    if (!feature.enabled) {
        profile(Modifier.fillMaxSize())
        return
    }
    var view by rememberSaveable(agentId) { mutableStateOf(AgentSettingsView.Profile.name) }
    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm)) {
            AgentSettingsView.entries.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = view == option.name,
                    onClick = { view = option.name },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = AgentSettingsView.entries.size),
                ) { Text(option.label) }
            }
        }
        if (view == AgentSettingsView.Secrets.name) {
            DesktopAgentVault(agentId, scope, Modifier.weight(1f))
        } else {
            profile(Modifier.weight(1f))
        }
    }
}

@Composable
private fun DesktopAgentVault(agentId: String, scope: CoroutineScope, modifier: Modifier) {
    val vault = remember(agentId, scope) { AgentVaultController(DesktopWorkspaceSources().secrets(), scope) }
    LaunchedEffect(vault) { vault.selectAgent(agentId) }
    DisposableEffect(vault) { onDispose { vault.close() } }
    val state by vault.state.collectAsState()
    AgentVaultPage(state = state, actions = vault, modifier = modifier, options = AgentVaultPageOptions(showTitle = false))
}
