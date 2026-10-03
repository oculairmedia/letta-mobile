package com.letta.mobile.ui.chat.surface.composer

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.composer_actions_title
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.TouchComposerDimens
import org.jetbrains.compose.resources.stringResource

/** One row of the Touch composer's "+" sheet. */
@Immutable
internal class TouchSheetItem(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * letta-mobile-bglj6.1.9: the "+" sheet, "Add to message". A port of the legacy Android composer's
 * ChatComposerActionSheet on designsystem's ActionSheet: a modal bottom sheet with a title, a
 * divider and one tonal row per action, each closing the sheet before it acts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TouchComposerActionSheet(items: List<TouchSheetItem>, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
        modifier = Modifier.testTag(ComposerTestTags.TOUCH_SHEET),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = LettaDimens.Space.xxl)) {
            Text(
                text = stringResource(Res.string.composer_actions_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.sm),
            )
            HorizontalDivider(Modifier.padding(vertical = LettaDimens.Space.sm))
            Column(Modifier.heightIn(max = TouchComposerDimens.sheetListMaxHeight).verticalScroll(rememberScrollState())) {
                items.forEach { item ->
                    TouchSheetRow(item) {
                        onDismiss()
                        item.onClick()
                    }
                }
            }
        }
    }
}

@Composable
private fun TouchSheetRow(item: TouchSheetItem, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            onClick()
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.xs),
        shape = RoundedCornerShape(TouchComposerDimens.sheetItemCorner),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = TouchComposerDimens.sheetItemElevation,
    ) {
        ListItem(
            headlineContent = { Text(item.label) },
            leadingContent = {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    modifier = Modifier.size(TouchComposerDimens.sheetIcon),
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}
