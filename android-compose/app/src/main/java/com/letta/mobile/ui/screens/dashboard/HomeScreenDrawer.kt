package com.letta.mobile.ui.screens.dashboard

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.haptics.HapticEffects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.material3.DrawerState

internal data class HomeScreenDrawerParams(
    val state: DashboardUiState,
    val navigation: HomeNavigationCallbacks,
    val viewModel: DashboardViewModel,
    val drawerState: DrawerState,
    val scope: CoroutineScope,
)

@Composable
internal fun HomeScreenDrawerContent(
    params: HomeScreenDrawerParams,
) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        Text(
            text = "Letta",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
        )

        var previousGroup: DashboardShortcut.Group? = null
        DashboardShortcut.entries.forEach { shortcut ->
            if (previousGroup != null && shortcut.group != previousGroup) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 28.dp),
                )
            }
            previousGroup = shortcut.group

            key(shortcut) {
                HomeDrawerShortcutRow(
                    shortcut = shortcut,
                    params = params,
                )
            }
        }
    }
}

@Composable
private fun HomeDrawerShortcutRow(
    shortcut: DashboardShortcut,
    params: HomeScreenDrawerParams,
) {
    val isPinned = params.state.pinnedItems.any {
        it is PinnedItem.Shortcut && it.value == shortcut
    }
    val context = LocalContext.current
    val label = stringResource(shortcut.labelResId)
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current

    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .combinedClickable(
                onClick = {
                    params.scope.launch { params.drawerState.close() }
                    params.navigation.shortcutNavigator(shortcut, params.state)()
                },
                onLongClick = {
                    HapticEffects.longPress(haptic, view)
                    toggleDrawerShortcutPin(
                        DrawerShortcutPinAction(
                            shortcut = shortcut,
                            isPinned = isPinned,
                            label = label,
                            context = context,
                            viewModel = params.viewModel,
                        ),
                    )
                },
            )
            .padding(start = 16.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            shortcut.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class DrawerShortcutPinAction(
    val shortcut: DashboardShortcut,
    val isPinned: Boolean,
    val label: String,
    val context: android.content.Context,
    val viewModel: DashboardViewModel,
)

private fun toggleDrawerShortcutPin(action: DrawerShortcutPinAction) {
    if (action.isPinned) {
        action.viewModel.unpinShortcut(action.shortcut)
        android.widget.Toast
            .makeText(action.context, "${action.label} unpinned", android.widget.Toast.LENGTH_SHORT)
            .show()
    } else {
        action.viewModel.pinShortcut(action.shortcut)
        android.widget.Toast
            .makeText(action.context, "${action.label} pinned", android.widget.Toast.LENGTH_SHORT)
            .show()
    }
}
