package com.letta.mobile.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.haptics.HapticEffects
import com.letta.mobile.ui.icons.LettaIconSizing


/**
 * A reusable bottom sheet for contextual / destructive action menus.
 *
 * Usage:
 * ```
 * ActionSheet(show = showSheet, onDismiss = { showSheet = false }) {
 *     ActionSheetItem(text = "Edit", icon = LettaIcons.Edit, onClick = { ... })
 *     ActionSheetItem(text = "Delete", icon = LettaIcons.Delete, onClick = { ... }, destructive = true)
 * }
 * ```
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(
    show: Boolean,
    onDismiss: () -> Unit,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (!show) return

    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            content()
        }
    }
}

/**
 * A single action inside an [ActionSheet].
 *
 * @param destructive When true, text and icon are tinted with `colorScheme.error`.
 */
@Composable
fun ActionSheetItem(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    destructive: Boolean = false,
    enabled: Boolean = true,
    supportingText: String? = null,
) {
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    val tint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
    }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val corner by animateDpAsState(
        targetValue = if (isPressed) 12.dp else 8.dp,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "actionSheetItemCorner",
    )
    val elevation by animateDpAsState(
        targetValue = if (isPressed) 4.dp else 2.dp,
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = "actionSheetItemElevation",
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(corner),
        color = LettaCardDefaults.listContainerColor,
        tonalElevation = elevation,
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = text,
                    color = when {
                        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                        destructive -> MaterialTheme.colorScheme.error
                        else -> Color.Unspecified
                    },
                )
            },
            supportingContent = supportingText?.let { text -> { Text(text) } },
            leadingContent = {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(LettaIconSizing.ListLeading),
                    tint = tint,
                )
            },
            modifier = Modifier.clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = {
                    if (destructive) {
                        HapticEffects.reject(haptic, view)
                    } else {
                        HapticEffects.contextClick(haptic, view)
                    }
                    onClick()
                },
            ),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}
