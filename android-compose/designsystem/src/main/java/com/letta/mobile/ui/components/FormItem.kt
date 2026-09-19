package com.letta.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.theme.LettaDimens

@Composable
fun FormItem(
    modifier: Modifier = Modifier,
    label: @Composable () -> Unit,
    description: @Composable (() -> Unit)? = null,
    tail: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
            modifier = Modifier.weight(1f),
        ) {
            ProvideTextStyle(value = MaterialTheme.typography.titleMedium) {
                label()
            }
            ProvideTextStyle(
                value = MaterialTheme.typography.labelSmall.copy(
                    color = LocalContentColor.current.copy(alpha = 0.6f)
                )
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
                    description?.invoke()
                }
            }
            content()
        }
        tail()
    }
}
