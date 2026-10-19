package com.letta.mobile.ui.modelcontrol

import androidx.compose.ui.graphics.vector.ImageVector
import com.letta.mobile.data.repository.modelcontrol.ConnectableProvider
import com.letta.mobile.ui.providers.icons.ProviderIcons

/**
 * Every name a provider row's logo may be found by, most specific first:
 * the row id, its subscription login, its type and name, then the display name.
 */
internal fun ConnectableProvider.brandIds(): List<String?> =
    listOf(id, oauthProviderId, providerType, providerName, displayName)

/** The provider's logo, or null when [ProviderIcons] has none for any of its names. */
internal fun ConnectableProvider.logoOrNull(): ImageVector? = ProviderIcons.brandOfAny(brandIds())?.logo
