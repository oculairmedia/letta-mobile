package com.letta.mobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.data.model.BackendConnectionStatus
import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.ui.theme.LettaDimens

/** What the Settings page shows: the saved backend and how its connection stands. */
@Immutable
data class SettingsPageState(
    val config: LettaConfig,
    val connection: BackendConnectionStatus = BackendConnectionStatus.Unconfigured,
)

object SettingsPageTags {
    const val PAGE = "settings-page"
    const val BACK = "settings-page-back"
}

/**
 * letta-mobile-ckmub.8: the Settings page shared by every client's shell: the title (with a back
 * arrow when the host gives [onBack]), the connection's status, the Backend card, and any further
 * cards the host has ([extraCards], e.g. the desktop's local runtime and permission cards). The
 * content keeps to a readable width and scrolls.
 */
@Composable
fun SettingsPage(
    state: SettingsPageState,
    actions: BackendSettingsActions,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    extraCards: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(LettaDimens.Space.xl)
            .testTag(SettingsPageTags.PAGE),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xl),
    ) {
        SettingsHeader(onBack)
        Column(
            modifier = Modifier.widthIn(max = LettaDimens.Pane.contentMaxWidth).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.lg),
        ) {
            BackendConnectionStatusRow(state.connection)
            (state.connection as? BackendConnectionStatus.Failed)?.let { failed ->
                Text(failed.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            BackendSettingsCard(config = state.config, actions = actions)
            extraCards()
        }
    }
}

@Composable
private fun SettingsHeader(onBack: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm)) {
        onBack?.let { back ->
            IconButton(onClick = back, modifier = Modifier.testTag(SettingsPageTags.BACK)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        }
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
    }
}
