package com.letta.mobile.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.ui.theme.LettaDimens

/** The banner's one action: its label and what it does. */
data class LettaNoticeAction(val label: String, val onClick: () -> Unit)

object LettaNoticeBannerTags {
    const val BANNER = "letta-notice-banner"
}

/**
 * letta-mobile-ckmub.3.12: a full-width error-toned notice over a page (the bundled runtime is
 * down, restarting or switched off) with an optional fix-it [action].
 */
@Composable
fun LettaNoticeBanner(text: String, modifier: Modifier = Modifier, action: LettaNoticeAction? = null) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = LettaDimens.Space.xl, vertical = LettaDimens.Space.md)
            .testTag(LettaNoticeBannerTags.BANNER),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        action?.let { Button(onClick = it.onClick) { Text(it.label) } }
    }
}
