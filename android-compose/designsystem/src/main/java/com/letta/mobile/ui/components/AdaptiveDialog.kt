package com.letta.mobile.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.letta.mobile.ui.shell.header.HeaderControls
import com.letta.mobile.ui.shell.header.HeaderNav
import com.letta.mobile.ui.shell.header.HeaderTitle
import com.letta.mobile.ui.shell.header.ShellHeader
import com.letta.mobile.ui.shell.scaffold.LettaPageScaffold
import com.letta.mobile.ui.theme.LettaDimens

/**
 * A full-screen dialog on a compact window and a card dialog on a wider one, both titled by the
 * shared [ShellHeader] with a close control (letta-mobile-ckmub.3.25). The full-screen form is a
 * [LettaPageScaffold], so it owns its insets like every page.
 */
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
                LettaPageScaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = MaterialTheme.colorScheme.surface,
                    header = { AdaptiveDialogHeader(title, onDismiss) },
                ) { paddingValues ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .padding(LettaDimens.Space.lg),
                    ) {
                        content()
                    }
                }
            }
        } else {
            Dialog(onDismissRequest = onDismiss) {
                Card(
                    modifier = modifier
                        .widthIn(max = 900.dp)
                        .fillMaxWidth()
                        .padding(LettaDimens.Orb.lg),
                ) {
                    AdaptiveDialogHeader(title, onDismiss)
                    Box(modifier = Modifier.padding(LettaDimens.Space.lg)) {
                        content()
                    }
                }
            }
        }
    }
}

@Composable
private fun AdaptiveDialogHeader(title: String, onDismiss: () -> Unit) {
    ShellHeader(
        title = HeaderTitle.Text(title),
        controls = HeaderControls(nav = HeaderNav.Close(onDismiss)),
    )
}
