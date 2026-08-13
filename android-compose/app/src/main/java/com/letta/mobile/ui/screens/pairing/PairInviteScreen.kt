package com.letta.mobile.ui.screens.pairing

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.ui.icons.LettaIcons
import kotlinx.coroutines.delay

/**
 * Server-mode invite-generation screen (letta-mobile-g2d2i): mints a QR via
 * [PairInviteViewModel], renders it with [QrMatrixCanvas], and shows the
 * suggested peer name plus a live countdown to expiry.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PairInviteScreen(
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PairInviteViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.screen_pairing_invite_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::regenerate, enabled = !uiState.loading) {
                        Icon(LettaIcons.Refresh, stringResource(R.string.pairing_regenerate))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Open the Letta app on the other device, choose \"Scan a pairing code\", and point its camera at this code.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            QrCard(uiState = uiState)
            InviteFooter(uiState = uiState)
        }
    }
}

@Composable
private fun QrCard(uiState: PairInviteUiState) {
    Box(
        modifier = Modifier
            .size(320.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        val matrix = uiState.matrix
        when {
            uiState.loading && matrix == null -> CircularProgressIndicator()
            matrix != null -> QrMatrixCanvas(matrix = matrix, modifier = Modifier.fillMaxSize())
            else -> Text(
                text = uiState.error ?: "Preparing pairing code…",
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun InviteFooter(uiState: PairInviteUiState) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val suggested = uiState.suggestedName
        if (!suggested.isNullOrBlank()) {
            Text(
                text = "Suggested name: $suggested",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val expiresAtMs = uiState.expiresAtMs
        if (expiresAtMs != null) {
            ExpiryCountdown(expiresAtMs = expiresAtMs)
        }
        if (uiState.error != null && uiState.matrix != null) {
            // A stale-but-still-shown QR plus a fresh error from a failed
            // regenerate attempt — surface both rather than hiding the code.
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = uiState.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ExpiryCountdown(expiresAtMs: Long) {
    var remainingMs by remember(expiresAtMs) {
        mutableLongStateOf(expiresAtMs - System.currentTimeMillis())
    }
    LaunchedEffect(expiresAtMs) {
        while (true) {
            remainingMs = expiresAtMs - System.currentTimeMillis()
            if (remainingMs <= 0L) break
            delay(1_000L)
        }
    }
    val text = if (remainingMs <= 0L) {
        "Expired — tap refresh for a new code"
    } else {
        val totalSeconds = remainingMs / 1_000L
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        "Expires in %d:%02d".format(minutes, seconds)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (remainingMs in 1..30_000L) FontWeight.SemiBold else FontWeight.Normal,
        color = if (remainingMs <= 0L) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}
