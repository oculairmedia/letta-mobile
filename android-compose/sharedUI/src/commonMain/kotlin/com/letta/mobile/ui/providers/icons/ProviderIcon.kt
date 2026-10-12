package com.letta.mobile.ui.providers.icons

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import com.letta.mobile.ui.theme.LettaDimens

object ProviderIconTags {
    /** A tile drawing the brand logo: prefix + the first id passed. */
    const val LOGO_PREFIX = "provider_icon_logo_"

    /** A tile drawing the initial fallback: prefix + the first id passed. */
    const val INITIAL_PREFIX = "provider_icon_initial_"
}

/**
 * Who a [ProviderIcon] is for: [id] is matched first, then [aliases] (type, OAuth id, display name…);
 * [label] gives the fallback initial.
 */
@Immutable
data class ProviderIdentity(val id: String, val label: String = id, val aliases: List<String?> = emptyList())

/** The logo sits inside the tile with a margin, like a glyph in an orb. */
private const val LOGO_FRACTION = 0.55f

/**
 * A provider's mark (letta-mobile-zxk2n.35): a circle with its brand logo, or — the documented
 * fallback for a provider [ProviderIcons] has no logo for — its initial. Filled with the primary
 * container when [connected], as the initial tile always was.
 */
@Composable
fun ProviderIcon(
    provider: ProviderIdentity,
    modifier: Modifier = Modifier,
    size: Dp = LettaDimens.Orb.md,
    connected: Boolean = false,
) {
    val brand = remember(provider) { ProviderIcons.brandOfAny(listOf(provider.id) + provider.aliases) }
    val container = if (connected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest
    val content = if (connected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val tag = if (brand != null) ProviderIconTags.LOGO_PREFIX else ProviderIconTags.INITIAL_PREFIX
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(size).background(container, CircleShape).testTag(tag + provider.id),
    ) {
        if (brand != null) {
            Icon(brand.logo, contentDescription = null, tint = content, modifier = Modifier.size(size * LOGO_FRACTION))
        } else {
            Text(text = provider.label.trim().take(1).uppercase(), style = MaterialTheme.typography.titleSmall, color = content)
        }
    }
}
