package com.letta.mobile.feature.chat.screen

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.chat.projection.toChatDisplayMode
import com.letta.mobile.ui.theme.LocalChatFontScale

/**
 * Collapsible chip for synthetic skill-instruction envelopes.
 *
 * Collapsed shows: 🧩 Skill: asus-router — summary
 * Expanded (on tap, or when chatMode == Interactive/Debug) shows the full raw envelope
 * in a monospace scroll block.
 *
 * Styled as an 8dp outline chip matching tool-call cards (accent color, silent-success).
 *
 * letta-mobile-o7ua9
 */
@Composable
fun SkillEnvelopeChip(
    slug: String,
    name: String,
    description: String,
    args: String,
    rawContent: String,
    chatMode: String,
    modifier: Modifier = Modifier,
) {
    // Auto-expand in Interactive/Debug mode, collapsed in Simple mode
    val mode = chatMode.toChatDisplayMode()
    val autoExpanded = mode != com.letta.mobile.data.chat.projection.ChatDisplayMode.Simple
    var userExpanded by remember { mutableStateOf(false) }
    val expanded = autoExpanded || userExpanded

    val fontScale = LocalChatFontScale.current

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { userExpanded = !userExpanded }
            .animateContentSize(),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.primary,
        ),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Collapsed header: 🧩 Skill: slug — args
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "🧩",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize * fontScale),
                )
                Text(
                    text = "Skill: $slug",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize * fontScale),
                    color = MaterialTheme.colorScheme.primary,
                )
                if (args.isNotBlank()) {
                    Text(
                        text = "— $args",
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize * fontScale),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Expanded: full raw envelope in monospace scroll block
            if (expanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(4.dp),
                        )
                        .padding(8.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = rawContent,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = MaterialTheme.typography.bodySmall.fontSize * fontScale,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}
