package com.letta.mobile.ui.modelcontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import com.letta.mobile.data.servicecredentials.ServiceCredentialDraft
import com.letta.mobile.data.servicecredentials.ServiceCredentialErrorCode
import com.letta.mobile.data.servicecredentials.ServiceCredentialStatus
import com.letta.mobile.data.servicecredentials.ServiceCredentialsController
import com.letta.mobile.data.servicecredentials.ServiceCredentialsState
import com.letta.mobile.data.servicecredentials.ServiceId
import com.letta.mobile.data.servicecredentials.ServiceTestState
import com.letta.mobile.ui.components.foundation.LettaStatusLabel
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens
import com.letta.mobile.ui.theme.tokens.LettaStatusTone

/** What the Services page does: open and close a form, save, test, remove and read the list again. */
data class ServiceCredentialActions(
    val onEdit: (ServiceId) -> Unit,
    val onCloseEdit: () -> Unit,
    val onSave: (ServiceId, ServiceCredentialDraft) -> Unit,
    val onTest: (ServiceId) -> Unit,
    val onRemove: (ServiceId) -> Unit,
    val onRefresh: () -> Unit,
) {
    companion object {
        /** The actions of [controller]. */
        fun bind(controller: ServiceCredentialsController) = ServiceCredentialActions(
            onEdit = controller::openEdit,
            onCloseEdit = controller::closeEdit,
            onSave = controller::save,
            onTest = controller::test,
            onRemove = controller::remove,
            onRefresh = controller::refresh,
        )
    }
}

/** The Services page's state and actions, as the Providers pane hosts it. */
class ServiceCredentialsBinding(val state: ServiceCredentialsState, val actions: ServiceCredentialActions)

object ServiceCredentialTags {
    const val PAGE = "service_credentials"
    const val CARD_PREFIX = "service_card_"
    const val TEST_PREFIX = "service_test_"
    const val EDIT_PREFIX = "service_edit_"
    const val FORM = "service_form"
    const val SAVE = "service_save"
    const val REMOVE = "service_remove"
    const val FIELD_PREFIX = "service_field_"
}

private val pagePadding = PaddingValues(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.md)

/**
 * Settings > Providers > Services: one card per service the host keeps a credential for (Mobbin:
 * Outseta and Apollo integration lists, a status beside each name and the action at the end). The
 * form is built from the host's description of the service, so a new service needs no new screen.
 */
@Composable
fun ServicesPage(binding: ServiceCredentialsBinding, modifier: Modifier = Modifier) {
    val state = binding.state
    LazyColumn(
        modifier = modifier.testTag(ServiceCredentialTags.PAGE),
        contentPadding = pagePadding,
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        item("subtitle") { ServiceHint(ServiceCredentialStrings.SUBTITLE) }
        when {
            !state.supported -> item("unsupported") { ServiceHint(ServiceCredentialStrings.UNSUPPORTED) }
            state.services.isEmpty() && !state.loading -> item("none") { ServiceHint(ServiceCredentialStrings.NONE) }
            !state.canManage -> item("read-only") { ServiceHint(ServiceCredentialStrings.READ_ONLY) }
        }
        if (state.failedService == null) state.error?.let { code -> item("error") { ServiceErrorLine(code) } }
        if (state.supported) {
            items(state.services, key = { "service-${it.serviceId.value}" }) { service -> ServiceCard(service, binding) }
        }
    }
}

@Composable
private fun ServiceCard(service: ServiceCredentialStatus, binding: ServiceCredentialsBinding) {
    val state = binding.state
    val editing = state.editing == service.serviceId
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().testTag("${ServiceCredentialTags.CARD_PREFIX}${service.serviceId.value}"),
    ) {
        Column(modifier = Modifier.padding(LettaDimens.Space.md), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
            Text(service.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ServiceStatusLabels(service)
            if (service.summary.isNotBlank()) ServiceHint(service.summary)
            if (state.canManage && !editing) CardActions(service, binding)
            if (!editing && state.failedService == service.serviceId) state.error?.let { ServiceErrorLine(it) }
            if (editing && state.canManage) ServiceCredentialForm(service, binding)
        }
    }
}

/** Key presence and the last test, as status labels ("✓ Key saved", "• Not tested"). */
@Composable
internal fun ServiceStatusLabels(service: ServiceCredentialStatus) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md)) {
        when {
            service.hasSecret -> LettaStatusLabel(ServiceCredentialStrings.KEY_SAVED, LettaStatusTone.Success, icon = LettaIcons.Check)
            service.configured -> LettaStatusLabel(ServiceCredentialStrings.NO_KEY, LettaStatusTone.Warning)
            else -> LettaStatusLabel(ServiceCredentialStrings.NOT_SET_UP, LettaStatusTone.Idle)
        }
        if (service.configured) {
            val test = service.lastTest
            when (test.state) {
                ServiceTestState.PASSED -> LettaStatusLabel(ServiceCredentialStrings.TEST_PASSED, LettaStatusTone.Success)
                ServiceTestState.FAILED -> test.error?.let { LettaStatusLabel(ServiceCredentialStrings.error(it), LettaStatusTone.Error) }
                ServiceTestState.UNKNOWN -> LettaStatusLabel(ServiceCredentialStrings.NOT_TESTED, LettaStatusTone.Idle)
            }
        }
    }
}

@Composable
private fun CardActions(service: ServiceCredentialStatus, binding: ServiceCredentialsBinding) {
    val busy = binding.state.busy
    val id = service.serviceId
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
        modifier = Modifier.padding(top = LettaDimens.Space.xs),
    ) {
        if (service.hasSecret) {
            OutlinedButton(
                onClick = { binding.actions.onTest(id) },
                enabled = busy == null,
                modifier = Modifier.testTag("${ServiceCredentialTags.TEST_PREFIX}${id.value}"),
            ) { Text(if (busy == id) ServiceCredentialStrings.TESTING else ServiceCredentialStrings.TEST) }
        }
        OutlinedButton(
            onClick = { binding.actions.onEdit(id) },
            enabled = busy == null,
            modifier = Modifier.testTag("${ServiceCredentialTags.EDIT_PREFIX}${id.value}"),
        ) { Text(if (service.configured) ServiceCredentialStrings.EDIT else ServiceCredentialStrings.SET_UP) }
    }
}

@Composable
internal fun ServiceHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun ServiceErrorLine(code: ServiceCredentialErrorCode) {
    Text(ServiceCredentialStrings.error(code), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}
