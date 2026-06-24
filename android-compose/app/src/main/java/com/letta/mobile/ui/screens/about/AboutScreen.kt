package com.letta.mobile.ui.screens.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.letta.mobile.R
import com.letta.mobile.ui.components.ConfirmDialog
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaTopBarDefaults
import com.letta.mobile.ui.theme.sectionTitle
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit,
    onLogout: () -> Unit,
    appVersion: String = "1.0.0",
) {
    var showLogoutDialog by remember { mutableStateOf(false) }
    val libraries by produceLibraries(R.raw.aboutlibraries)

    Scaffold(
        containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_about_title)) },
                colors = LettaTopBarDefaults.topAppBarColors(),
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { paddingValues ->
        LibrariesContainer(
            libraries = libraries,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(bottom = 24.dp),
            showDescription = false,
            showFundingBadges = false,
            divider = {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp))
            },
            header = {
                item(key = "about-header") {
                    AboutHeader(
                        appVersion = appVersion,
                        onClearDataClick = { showLogoutDialog = true },
                    )
                }
                item(key = "about-open-source-title") {
                    Text(
                        text = stringResource(R.string.screen_about_open_source_title),
                        style = MaterialTheme.typography.sectionTitle,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp),
                    )
                }
            },
        )
    }

    ConfirmDialog(
        show = showLogoutDialog,
        title = stringResource(R.string.screen_about_clear_data_dialog_title),
        message = stringResource(R.string.screen_about_clear_data_dialog_message),
        confirmText = stringResource(R.string.screen_about_clear_data_confirm),
        dismissText = stringResource(R.string.action_cancel),
        onConfirm = { showLogoutDialog = false; onLogout() },
        onDismiss = { showLogoutDialog = false },
        destructive = true,
    )
}

@Composable
private fun AboutHeader(
    appVersion: String,
    onClearDataClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 24.dp),
    ) {
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.screen_about_version_format, appVersion),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            text = stringResource(R.string.screen_about_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp),
        )
        OutlinedButton(
            onClick = onClearDataClick,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text(stringResource(R.string.screen_about_clear_data_button))
        }
    }
}
