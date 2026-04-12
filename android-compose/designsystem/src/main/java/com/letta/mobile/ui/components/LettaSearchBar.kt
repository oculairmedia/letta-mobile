package com.letta.mobile.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.icons.LettaIcons

/**
 * Unified pill-shaped search bar used across all screens.
 *
 * @param query Current search text.
 * @param onQueryChange Called when the text changes.
 * @param onClear Called when the user taps the clear button.
 * @param placeholder Placeholder text shown when empty.
 * @param compact When true, uses smaller text and icon sizing for sub-screens.
 */
@Composable
fun LettaSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Search\u2026",
    compact: Boolean = false,
    searchIconContentDescription: String? = null,
    clearIconContentDescription: String = "Clear search",
) {
    val colorScheme = MaterialTheme.colorScheme
    val iconSize = if (compact) 18.dp else 20.dp
    val textStyle = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium

    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.then(if (compact) Modifier.heightIn(min = 40.dp) else Modifier),
        placeholder = {
            Text(
                placeholder,
                style = textStyle,
            )
        },
        leadingIcon = {
            Icon(
                LettaIcons.Search,
                contentDescription = searchIconContentDescription,
                modifier = Modifier.size(iconSize),
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear) {
                    Icon(
                        LettaIcons.Clear,
                        contentDescription = clearIconContentDescription,
                        modifier = Modifier.size(iconSize),
                    )
                }
            }
        },
        singleLine = true,
        textStyle = textStyle,
        shape = RoundedCornerShape(24.dp),
        colors = TextFieldDefaults.colors(
            unfocusedContainerColor = colorScheme.surfaceContainerHigh,
            focusedContainerColor = colorScheme.surfaceContainerHigh,
            unfocusedIndicatorColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            cursorColor = colorScheme.primary,
        ),
    )
}
