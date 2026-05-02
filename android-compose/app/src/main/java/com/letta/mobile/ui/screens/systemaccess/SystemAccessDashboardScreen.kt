package com.letta.mobile.ui.screens.systemaccess

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.letta.mobile.R
import com.letta.mobile.platform.SystemAccessFlavor
import com.letta.mobile.platform.systemaccess.SystemAccessApprovalPolicy
import com.letta.mobile.platform.systemaccess.SystemAccessCapability
import com.letta.mobile.platform.systemaccess.SystemAccessCapabilityStatus
import com.letta.mobile.platform.systemaccess.SystemAccessFlavorAvailability
import com.letta.mobile.platform.systemaccess.SystemAccessPermissionIntent
import com.letta.mobile.platform.systemaccess.SystemAccessPermissionIntentKind
import com.letta.mobile.platform.systemaccess.SystemAccessPolicyRiskLevel
import com.letta.mobile.ui.common.LocalSnackbarDispatcher
import com.letta.mobile.ui.common.UiState
import com.letta.mobile.ui.components.CardGroup
import com.letta.mobile.ui.components.ErrorContent
import com.letta.mobile.ui.components.ShimmerCard
import com.letta.mobile.ui.components.StatusChip
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaTopBarDefaults
import com.letta.mobile.ui.theme.listItemMetadata
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemAccessDashboardScreen(
    onNavigateBack: () -> Unit,
    viewModel: SystemAccessDashboardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val permissionIntentHandler = systemAccessPermissionIntentHandler(
        context = context,
        onRefresh = viewModel::refresh,
    )

    RefreshSystemAccessOnResume(onRefresh = viewModel::refresh)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = LettaTopBarDefaults.scaffoldContainerColor(),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.screen_system_access_title)) },
                colors = LettaTopBarDefaults.largeTopAppBarColors(),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(LettaIcons.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::refresh) {
                        Icon(LettaIcons.Refresh, stringResource(R.string.action_refresh))
                    }
                },
            )
        },
    ) { paddingValues ->
        SystemAccessDashboardBody(
            uiState = uiState,
            onRetry = viewModel::refresh,
            onPermissionIntentClick = permissionIntentHandler,
            modifier = Modifier.padding(paddingValues),
        )
    }
}

@Composable
private fun systemAccessPermissionIntentHandler(
    context: Context,
    onRefresh: () -> Unit,
): (SystemAccessPermissionIntent) -> Unit {
    val snackbar = LocalSnackbarDispatcher.current
    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) {
        onRefresh()
    }
    val documentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val message = if (persistSafGrant(context, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)) {
                R.string.screen_system_access_saf_file_granted
            } else {
                R.string.screen_system_access_saf_grant_failed
            }
            snackbar.dispatch(context.getString(message))
        }
        onRefresh()
    }
    val treeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (uri != null) {
            val message = if (persistSafGrant(context, uri, flags)) {
                R.string.screen_system_access_saf_folder_granted
            } else {
                R.string.screen_system_access_saf_grant_failed
            }
            snackbar.dispatch(context.getString(message))
        }
        onRefresh()
    }

    return { intent ->
        val canOpenSettings = intent.kind == SystemAccessPermissionIntentKind.SettingsDeepLink &&
            intent.settingsAction != null
        if (canOpenSettings) {
            try {
                settingsLauncher.launch(intent.toAndroidIntent(context))
            } catch (_: ActivityNotFoundException) {
                snackbar.dispatch(context.getString(R.string.screen_system_access_settings_unavailable))
            }
        } else if (intent.id == "storage.saf.open_document") {
            documentLauncher.launch(arrayOf("*/*"))
        } else if (intent.id == "storage.saf.open_tree") {
            treeLauncher.launch(null)
        } else {
            snackbar.dispatch(context.getString(R.string.screen_system_access_action_future))
        }
    }
}

@Composable
private fun RefreshSystemAccessOnResume(onRefresh: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                onRefresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun SystemAccessDashboardBody(
    uiState: UiState<SystemAccessDashboardUiState>,
    onRetry: () -> Unit,
    onPermissionIntentClick: (SystemAccessPermissionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (val state = uiState) {
        is UiState.Loading -> {
            Column(modifier = modifier.padding(16.dp)) {
                ShimmerCard(modifier = Modifier.fillMaxWidth())
            }
        }
        is UiState.Error -> ErrorContent(
            message = state.message,
            onRetry = onRetry,
            modifier = modifier,
        )
        is UiState.Success -> SystemAccessDashboardContent(
            state = state.data,
            onPermissionIntentClick = onPermissionIntentClick,
            modifier = modifier,
        )
    }
}

@Composable
private fun SystemAccessDashboardContent(
    state: SystemAccessDashboardUiState,
    onPermissionIntentClick: (SystemAccessPermissionIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("summary") {
            SystemAccessSummaryCard(state = state)
        }

        items(state.groups, key = { it.family.name }) { group ->
            CardGroup(title = { Text(group.family.label) }) {
                group.capabilities.forEach { capability ->
                    item(
                        headlineContent = { CapabilityHeadline(capability = capability) },
                        supportingContent = {
                            CapabilityDetails(
                                capability = capability,
                                flavor = state.flavor,
                                onPermissionIntentClick = onPermissionIntentClick,
                            )
                        },
                        leadingContent = { Icon(group.family.icon, contentDescription = null) },
                    )
                }
            }
        }

        item("bottom_spacer") { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun SystemAccessSummaryCard(state: SystemAccessDashboardUiState) {
    CardGroup {
        item(
            headlineContent = { Text(stringResource(R.string.screen_system_access_summary_headline)) },
            supportingContent = {
                Text(
                    text = stringResource(
                        R.string.screen_system_access_summary_body,
                        state.grantedCount,
                        state.visibleCount,
                        state.flavor.label,
                    ),
                )
            },
            leadingContent = { Icon(LettaIcons.Key, contentDescription = null) },
        )
    }
}

@Composable
private fun CapabilityHeadline(capability: SystemAccessCapability) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = capability.title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            StatusChip(status = capability.status.label)
        }
        Text(
            text = capability.summary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapabilityDetails(
    capability: SystemAccessCapability,
    flavor: SystemAccessFlavor,
    onPermissionIntentClick: (SystemAccessPermissionIntent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = capability.statusReason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MetadataChip("Flavor: ${capability.flavorAvailability.availabilityFor(flavor).label}")
            MetadataChip("Risk: ${capability.policyRisk.level.label}")
            MetadataChip("Data: ${capability.dataSensitivity.name.toDisplayLabel()}")
            MetadataChip("Approval: ${capability.approvalPolicy.label}")
            MetadataChip(if (capability.userEnabled) "Enabled" else "Disabled by default")
        }

        HorizontalDivider()

        DetailLine(
            label = stringResource(R.string.screen_system_access_tools_label),
            value = capability.toolIds.joinToString().ifBlank { stringResource(R.string.common_none) },
        )
        DetailLine(
            label = stringResource(R.string.screen_system_access_audit_label),
            value = capability.auditSummary,
        )
        DetailLine(
            label = stringResource(R.string.screen_system_access_risk_label),
            value = capability.policyRisk.rationale,
        )

        val actions = capability.permissionIntents.filter { intent ->
            capability.status != SystemAccessCapabilityStatus.Unavailable ||
                intent.kind == SystemAccessPermissionIntentKind.Documentation
        }
        if (actions.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                actions.forEach { intent ->
                    TextButton(onClick = { onPermissionIntentClick(intent) }) {
                        Text(intent.label)
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataChip(text: String) {
    AssistChip(
        onClick = {},
        enabled = false,
        label = {
            Text(
                text = text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.listItemMetadata,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun persistSafGrant(context: Context, uri: Uri, flags: Int): Boolean = try {
    context.contentResolver.takePersistableUriPermission(uri, flags)
    true
} catch (_: SecurityException) {
    false
}

private fun SystemAccessPermissionIntent.toAndroidIntent(context: Context): Intent {
    val action = checkNotNull(settingsAction)
    return Intent(action).apply {
        when (action) {
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            -> data = Uri.parse("package:${context.packageName}")
        }
        if (context !is Activity) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}

private val SystemAccessFlavor.label: String
    get() = when (this) {
        SystemAccessFlavor.Play -> "Play"
        SystemAccessFlavor.Sideload -> "Sideload"
        SystemAccessFlavor.Root -> "Root"
    }

private val SystemAccessCapabilityFamily.label: String
    get() = when (this) {
        SystemAccessCapabilityFamily.Storage -> "Storage"
        SystemAccessCapabilityFamily.PersonalData -> "Personal data"
        SystemAccessCapabilityFamily.CrossAppFramework -> "Cross-app / framework"
        SystemAccessCapabilityFamily.ShellDelegatedPrivilege -> "Shell / delegated privilege"
        SystemAccessCapabilityFamily.Root -> "Root"
    }

private val SystemAccessCapabilityFamily.icon
    get() = when (this) {
        SystemAccessCapabilityFamily.Storage -> LettaIcons.Storage
        SystemAccessCapabilityFamily.PersonalData -> LettaIcons.People
        SystemAccessCapabilityFamily.CrossAppFramework -> LettaIcons.Settings
        SystemAccessCapabilityFamily.ShellDelegatedPrivilege -> LettaIcons.Code
        SystemAccessCapabilityFamily.Root -> LettaIcons.Key
    }

private val SystemAccessCapabilityStatus.label: String
    get() = when (this) {
        SystemAccessCapabilityStatus.Unavailable -> "Unavailable"
        SystemAccessCapabilityStatus.AvailableNeedsSetup -> "Needs setup"
        SystemAccessCapabilityStatus.Denied -> "Denied"
        SystemAccessCapabilityStatus.Granted -> "Granted"
        SystemAccessCapabilityStatus.GrantedLimited -> "Limited"
        SystemAccessCapabilityStatus.Revoked -> "Revoked"
        SystemAccessCapabilityStatus.Error -> "Error"
    }

private val SystemAccessFlavorAvailability.label: String
    get() = when (this) {
        SystemAccessFlavorAvailability.Supported -> "Supported"
        SystemAccessFlavorAvailability.Unsupported -> "Unsupported"
        SystemAccessFlavorAvailability.PolicyGated -> "Policy gated"
        SystemAccessFlavorAvailability.DocumentationOnly -> "Docs only"
    }

private val SystemAccessPolicyRiskLevel.label: String
    get() = when (this) {
        SystemAccessPolicyRiskLevel.Low -> "Low"
        SystemAccessPolicyRiskLevel.Medium -> "Medium"
        SystemAccessPolicyRiskLevel.High -> "High"
        SystemAccessPolicyRiskLevel.VeryHigh -> "Very high"
        SystemAccessPolicyRiskLevel.NotPlayCompatible -> "Not Play-compatible"
    }

private val SystemAccessApprovalPolicy.label: String
    get() = when (this) {
        SystemAccessApprovalPolicy.None -> "None"
        SystemAccessApprovalPolicy.AskEveryTime -> "Ask every time"
        SystemAccessApprovalPolicy.RememberPerSession -> "Session"
        SystemAccessApprovalPolicy.RememberPerScope -> "Per scope"
        SystemAccessApprovalPolicy.Forbidden -> "Forbidden"
    }

private val SystemAccessCapability.auditSummary: String
    get() = buildString {
        append("Logs ")
        append(auditPolicy.loggedFields.joinToString())
        if (auditPolicy.redactedFields.isNotEmpty()) {
            append("; redacts ")
            append(auditPolicy.redactedFields.joinToString())
        }
        if (auditPolicy.localOnlyByDefault) {
            append("; local-only by default")
        }
    }

private fun String.toDisplayLabel(): String = replace('_', ' ')
    .lowercase(Locale.ROOT)
    .replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString() }
