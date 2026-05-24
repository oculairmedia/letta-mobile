package com.letta.mobile.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Shared card treatment for list, menu, and admin rows.
 *
 * These surfaces should read as quiet content containers on the app scaffold,
 * not as decorative cards. Keep richer motion and emphasis inside the row.
 */
object LettaCardDefaults {
    val listShape: Shape = RoundedCornerShape(12.dp)
    val prominentListShape: Shape = RoundedCornerShape(16.dp)
    val actionShape: Shape = RoundedCornerShape(8.dp)

    val listContainerColor: Color
        @Composable get() = MaterialTheme.colorScheme.surfaceContainerLow

    val listContentColor: Color
        @Composable get() = MaterialTheme.colorScheme.onSurface

    @Composable
    fun listCardColors(
        containerColor: Color = listContainerColor,
        contentColor: Color = listContentColor,
    ): CardColors = CardDefaults.cardColors(
        containerColor = containerColor,
        contentColor = contentColor,
    )

    @Composable
    fun listCardElevation(): CardElevation = CardDefaults.cardElevation(
        defaultElevation = 0.dp,
        pressedElevation = 1.dp,
        focusedElevation = 1.dp,
        hoveredElevation = 1.dp,
    )
}

@Composable
fun Modifier.expressiveContentSize(enabled: Boolean = true): Modifier =
    if (enabled) {
        animateContentSize(animationSpec = MaterialTheme.motionScheme.fastSpatialSpec())
    } else {
        this
    }
