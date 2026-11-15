package com.letta.mobile.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.letta.mobile.data.attachment.ImageIngressPolicy
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.tokens.LettaTextStyles
import com.letta.mobile.ui.theme.tokens.LettaSurfaceSizes
import com.letta.mobile.ui.theme.tokens.LettaComponentDimens
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.components.foundation.LettaIconStyle
import com.letta.mobile.ui.components.foundation.LettaIcon

/**
 * Full-content drop hint shown while an OS file drag hovers the window:
 * tells the user where the files will land and what is accepted.
 */
@Composable
fun ImageDropOverlay(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = LettaSurfaceSizes.dropScrimAlpha)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainer,
            border = BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.primary),
            shadowElevation = LettaDimens.Space.md,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = LettaDimens.Space.xxl, vertical = LettaDimens.Space.xxl),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
            ) {
                LettaIcon(
                    imageVector = LettaIcons.Image,
                    contentDescription = null,
                    style = LettaIconStyle(LettaComponentDimens.emptyStateIcon, MaterialTheme.colorScheme.primary),
                )
                Text(
                    text = "Drop images to attach",
                    style = LettaTextStyles.titleMediumSemibold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    // Derived from the shared policy so the hint can never
                    // drift from what the ingress actually accepts.
                    text = "Up to ${ImageIngressPolicy.MAX_FILES} per drop · " +
                        ImageIngressPolicy.supportedFormatsLabel(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
