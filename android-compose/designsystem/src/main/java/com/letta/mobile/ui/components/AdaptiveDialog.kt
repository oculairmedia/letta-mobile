package com.letta.mobile.ui.components

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.letta.mobile.ui.icons.LettaIcons

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveDialog(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints {
        val isCompact = maxWidth < 600.dp

        if (isCompact) {
            Dialog(
                onDismissRequest = onDismiss,
                properties = DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Scaffold(
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = { Text(title) },
                            navigationIcon = {
                                IconButton(onClick = onDismiss) {
                                    Icon(LettaIcons.Close, contentDescription = "Close")
                                }
                            },
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                ) { paddingValues ->
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .padding(16.dp),
                    ) {
                        content()
                    }
                }
            }
        } else {
            Dialog(onDismissRequest = onDismiss) {
                androidx.compose.material3.Card(
                    modifier = modifier
                        .widthIn(max = 900.dp)
                        .fillMaxWidth()
                        .padding(40.dp),
                ) {
                    CenterAlignedTopAppBar(
                        title = { Text(title) },
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(LettaIcons.Close, contentDescription = "Close")
                            }
                        },
                    )
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.padding(16.dp),
                    ) {
                        content()
                    }
                }
            }
        }
    }
}
