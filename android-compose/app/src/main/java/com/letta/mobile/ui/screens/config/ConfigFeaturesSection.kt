package com.letta.mobile.ui.screens.config

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ca.oculair.meridian.R
import com.letta.mobile.ui.components.CardGroup

// The settings screen's features card: the app-wide feature switches.

@Composable
internal fun FeaturesSection(
    state: ConfigUiState,
    callbacks: FeatureToggleCallbacks,
) {
    CardGroup(title = {
        ConfigSectionTitle(stringResource(R.string.screen_config_features_section))
    }) {
        item(
            headlineContent = { Text(stringResource(R.string.screen_config_enable_projects)) },
            supportingContent = { Text(stringResource(R.string.screen_config_enable_projects_description)) },
            trailingContent = {
                HapticSwitch(
                    checked = state.enableProjects,
                    onCheckedChange = callbacks.onEnableProjectsChange,
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.screen_config_haptics)) },
            supportingContent = { Text(stringResource(R.string.screen_config_haptics_description)) },
            trailingContent = {
                HapticSwitch(
                    checked = state.hapticsEnabled,
                    onCheckedChange = callbacks.onHapticsEnabledChange,
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.screen_config_shared_chat_page)) },
            supportingContent = { Text(stringResource(R.string.screen_config_shared_chat_page_description)) },
            trailingContent = {
                HapticSwitch(
                    checked = state.sharedChatPageEnabled,
                    onCheckedChange = callbacks.onSharedChatPageEnabledChange,
                )
            },
        )
        item(
            headlineContent = { Text(stringResource(R.string.screen_config_shared_nav_drawer)) },
            supportingContent = { Text(stringResource(R.string.screen_config_shared_nav_drawer_description)) },
            trailingContent = {
                HapticSwitch(
                    checked = state.sharedNavDrawerEnabled,
                    onCheckedChange = callbacks.onSharedNavDrawerEnabledChange,
                )
            },
        )
        if (state.sharedChatPageEnabled) {
            item(
                headlineContent = { Text(stringResource(R.string.screen_config_open_chats_on_canvas)) },
                supportingContent = { Text(stringResource(R.string.screen_config_open_chats_on_canvas_description)) },
                trailingContent = {
                    HapticSwitch(
                        checked = state.openChatsOnCanvas,
                        onCheckedChange = callbacks.onOpenChatsOnCanvasChange,
                    )
                },
            )
        }
    }
}
