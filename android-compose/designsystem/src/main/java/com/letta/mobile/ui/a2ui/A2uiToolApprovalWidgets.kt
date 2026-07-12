package com.letta.mobile.ui.a2ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.a2ui.A2uiComponent
import com.letta.mobile.data.a2ui.A2uiSurfaceState
import com.letta.mobile.ui.haptics.HapticEffects
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put


@Composable
internal fun A2uiToolApprovalCard(
    component: A2uiComponent,
    surface: A2uiSurfaceState,
    modifier: Modifier = Modifier,
    onAction: (A2uiAction) -> Unit,
) {
    val props = remember(component.raw) { component.toolApprovalProps() }
    if (props == null) {
        A2uiSkeletonCard(modifier = modifier.testTag(A2uiTestTags.MissingComponent))
        return
    }

    val riskStyle = props.risk.style()
    val haptic = LocalHapticFeedback.current
    val view = LocalView.current
    var result by remember(component.id, props.callId) { mutableStateOf<ToolApprovalResult?>(null) }
    var remainingSeconds by remember(component.id, props.callId, props.timeoutSeconds) {
        mutableStateOf(props.timeoutSeconds)
    }
    var argumentsExpanded by rememberA2uiLocalBooleanState("argumentsExpanded", true)
    var revealedSensitiveKeys by remember(component.id, props.callId) { mutableStateOf(emptySet<String>()) }

    fun dispatch(affordance: ToolApprovalAffordance) {
        if (result != null) return
        val next = ToolApprovalResult(
            decision = affordance.decision,
            scope = affordance.scope,
        )
        result = next
        if (affordance == ToolApprovalAffordance.Deny) {
            HapticEffects.reject(haptic, view)
        } else if (props.risk == ToolApprovalRisk.Destructive) {
            HapticEffects.longPress(haptic)
        }
                onAction(toolApprovalAction(surface, props.callId, next))
    }

    LaunchedEffect(component.id, props.callId, props.timeoutSeconds, result) {
        if (result != null || props.timeoutSeconds <= 0) return@LaunchedEffect
        while (remainingSeconds > 0 && result == null) {
            delay(1_000)
            remainingSeconds = (remainingSeconds - 1).coerceAtLeast(0)
        }
        if (remainingSeconds == 0 && result == null) {
            val timeout = ToolApprovalResult(decision = "timeout", scope = "timeout")
            result = timeout
            onAction(toolApprovalAction(surface, props.callId, timeout))
        }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "Tool approval for ${props.toolName}, ${props.risk.label} risk"
                stateDescription = result?.statusLabel() ?: "Awaiting approval"
            }
            .testTag(A2uiTestTags.ToolApprovalCard),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        border = BorderStroke(riskStyle.borderWidth, riskStyle.borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = props.toolName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    props.toolDescription?.takeIf { it.isNotBlank() }?.let { description ->
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                ToolApprovalRiskPill(props.risk, riskStyle)
            }

            props.rationale?.takeIf { it.isNotBlank() }?.let { rationale ->
                Text(
                    text = rationale,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (props.arguments.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Arguments",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { argumentsExpanded = !argumentsExpanded }) {
                            Text(if (argumentsExpanded) "Hide" else "Show")
                        }
                    }
                    if (argumentsExpanded) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f),
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                props.arguments.forEach { argument ->
                                    ToolApprovalArgumentRow(
                                        argument = argument,
                                        revealed = argument.key in revealedSensitiveKeys,
                                        onReveal = {
                                            revealedSensitiveKeys = revealedSensitiveKeys + argument.key
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            ToolApprovalStatusLine(
                result = result,
                remainingSeconds = remainingSeconds,
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                props.affordances.chunked(ToolApprovalButtonsPerRow).forEach { rowAffordances ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowAffordances.forEach { affordance ->
                            ToolApprovalAffordanceButton(
                                affordance = affordance,
                                risk = props.risk,
                                enabled = result == null,
                                modifier = Modifier.weight(1f),
                                onClick = { dispatch(affordance) },
                            )
                        }
                        if (rowAffordances.size < ToolApprovalButtonsPerRow) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

internal fun A2uiComponent.toolApprovalProps(): ToolApprovalProps? {
    val toolName = raw.stringValue("toolName", "name")?.takeIf { it.isNotBlank() } ?: return null
    val callId = raw.stringValue("callId")?.takeIf { it.isNotBlank() } ?: return null
    val arguments = (raw["arguments"] as? JsonArray)
        ?.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val key = obj.stringValue("key")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ToolApprovalArgument(
                key = key,
                value = obj.stringValue("value").orEmpty(),
                isSensitive = obj.booleanValue("isSensitive") ?: false,
            )
        }
        .orEmpty()
    val affordances = (raw["affordances"] as? JsonArray)
        ?.mapNotNull { element -> ToolApprovalAffordance.from(element.jsonPrimitiveOrNull?.contentOrNull) }
        ?.takeIf { it.isNotEmpty() }
        ?: ToolApprovalAffordance.Defaults

    return ToolApprovalProps(
        toolName = toolName,
        toolDescription = raw.stringValue("toolDescription", "description"),
        arguments = arguments,
        risk = ToolApprovalRisk.from(raw.stringValue("riskLevel")),
        rationale = raw.stringValue("rationale"),
        affordances = affordances,
        timeoutSeconds = (raw.intValue("timeoutSeconds") ?: DefaultToolApprovalTimeoutSeconds).coerceAtLeast(0),
        callId = callId,
    )
}

internal fun toolApprovalAction(
    surface: A2uiSurfaceState,
    callId: String,
    result: ToolApprovalResult,
): A2uiAction {
    val context = buildJsonObject {
        put("callId", callId)
        put("decision", result.decision)
        put("scope", result.scope)
        surface.approvalRequestId?.let { put("approvalRequestId", it) }
    }
    val raw = buildJsonObject {
        put("actionName", ToolApprovalResponseAction)
        put("name", ToolApprovalResponseAction)
        put("surfaceId", surface.surfaceId)
        put("context", context)
        surface.conversationId?.let { put("conversationId", it) }
        surface.runId?.let { put("runId", it) }
        surface.turnId?.let { put("turnId", it) }
        surface.approvalRequestId?.let { put("approvalRequestId", it) }
        put("actionId", callId)
    }
    return A2uiAction(
        name = ToolApprovalResponseAction,
        surfaceId = surface.surfaceId,
        context = context,
        conversationId = surface.conversationId,
        runId = surface.runId,
        turnId = surface.turnId,
        actionId = callId,
        raw = raw,
    )
}

@Composable
internal fun ToolApprovalRiskPill(
    risk: ToolApprovalRisk,
    style: ToolApprovalRiskStyle,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = style.pillContainer,
    ) {
        Text(
            text = risk.label,
            style = MaterialTheme.typography.labelSmall,
            color = style.pillContent,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
internal fun ToolApprovalArgumentRow(
    argument: ToolApprovalArgument,
    revealed: Boolean,
    onReveal: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = argument.key,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = 72.dp, max = 96.dp),
        )
        val valueModifier = if (argument.isSensitive && !revealed) {
            Modifier
                .clickable(onClickLabel = "Reveal value for ${argument.key}") { onReveal() }
                .testTag(A2uiTestTags.ToolApprovalSensitiveValue)
        } else {
            Modifier
        }
        Text(
            text = if (argument.isSensitive && !revealed) SensitiveMask else argument.value,
            modifier = valueModifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = if (revealed) 4 else 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ToolApprovalStatusLine(
    result: ToolApprovalResult?,
    remainingSeconds: Int,
) {
    val text = result?.statusLabel() ?: "Auto-denies in ${remainingSeconds}s"
    Text(
        text = text,
        modifier = Modifier.testTag(A2uiTestTags.ToolApprovalCountdown),
        style = MaterialTheme.typography.labelMedium,
        color = when (result?.decision) {
            "deny", "timeout" -> MaterialTheme.colorScheme.error
            "approve" -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
internal fun ToolApprovalAffordanceButton(
    affordance: ToolApprovalAffordance,
    risk: ToolApprovalRisk,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val buttonModifier = modifier.widthIn(min = 64.dp)
    if (affordance == ToolApprovalAffordance.Deny) {
        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = buttonModifier,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text(affordance.label)
        }
    } else {
        val colors = if (risk == ToolApprovalRisk.Destructive) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
        } else {
            ButtonDefaults.buttonColors()
        }
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = buttonModifier,
            colors = colors,
        ) {
            Text(affordance.label)
        }
    }
}
