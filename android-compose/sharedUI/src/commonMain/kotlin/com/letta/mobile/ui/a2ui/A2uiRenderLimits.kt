package com.letta.mobile.ui.a2ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.a2ui.A2uiLink
import com.letta.mobile.data.a2ui.A2uiLinkDecision
import com.letta.mobile.data.a2ui.A2uiLinkPolicy
import com.letta.mobile.data.a2ui.A2uiRenderBudget
import com.letta.mobile.data.a2ui.A2uiRenderClaim
import com.letta.mobile.data.a2ui.A2uiSurfaceLimits
import com.letta.mobile.data.model.A2uiComponentId

/** The render budget of the surface pass being composed; null outside a surface. */
internal val LocalA2uiRenderBudget = compositionLocalOf<A2uiRenderBudget?> { null }

/** How `openUrl` buttons treat a parsed link; hosts may provide a policy, the default asks every time. */
val LocalA2uiLinkPolicy = compositionLocalOf { A2uiLinkPolicy.ConfirmAll }

/**
 * The slot this component instance holds in the surface's [A2uiRenderBudget]. Claimed once per
 * (budget, instance) and released when the instance leaves composition, so recomposition never
 * re-counts an instance already on screen.
 */
@Composable
internal fun rememberA2uiRenderClaim(componentId: A2uiComponentId, renderScope: A2uiRenderScope): A2uiRenderClaim {
    val budget = LocalA2uiRenderBudget.current ?: return A2uiRenderClaim.Granted
    val scopeKey = renderScope.basePath.orEmpty()
    return remember(budget, componentId, scopeKey) { A2uiRenderSlot(budget, componentId, scopeKey) }.claim
}

private class A2uiRenderSlot(
    private val budget: A2uiRenderBudget,
    private val componentId: A2uiComponentId,
    private val scopeKey: String,
) : RememberObserver {
    val claim: A2uiRenderClaim = budget.claim(componentId, scopeKey)
    private var released = false

    override fun onRemembered() = Unit
    override fun onForgotten() = release()
    override fun onAbandoned() = release()

    private fun release() {
        if (released || claim != A2uiRenderClaim.Granted) return
        released = true
        budget.release(componentId, scopeKey)
    }
}

/** Renders at most [A2uiSurfaceLimits.MAX_CHILDREN_PER_CONTAINER] of [items], then a "N more not shown" line. */
@Composable
internal inline fun <T> A2uiCappedForEach(items: List<T>, content: @Composable (T) -> Unit) {
    items.take(A2uiSurfaceLimits.MAX_CHILDREN_PER_CONTAINER).forEach { content(it) }
    A2uiChildrenTruncated(total = items.size)
}

/** [A2uiCappedForEach] with the item index. */
@Composable
internal inline fun <T> A2uiCappedForEachIndexed(items: List<T>, content: @Composable (Int, T) -> Unit) {
    items.take(A2uiSurfaceLimits.MAX_CHILDREN_PER_CONTAINER).forEachIndexed { index, item -> content(index, item) }
    A2uiChildrenTruncated(total = items.size)
}

/** Shown after a container's capped children when [total] exceeded the cap. */
@Composable
internal fun A2uiChildrenTruncated(total: Int) {
    val hidden = total - A2uiSurfaceLimits.MAX_CHILDREN_PER_CONTAINER
    if (hidden <= 0) return
    Text(
        text = "$hidden more not shown",
        modifier = Modifier.testTag(A2uiTestTags.CHILDREN_TRUNCATED),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A component instance the render budget refused (pass exhausted, or the same node repeated too often). */
@Composable
internal fun A2uiRenderLimitPlaceholder(modifier: Modifier = Modifier) {
    A2uiSkeletonLine(modifier = modifier.testTag(A2uiTestTags.RENDER_LIMIT))
}

/** A surface whose update broke [A2uiSurfaceLimits] and was refused. */
@Composable
internal fun A2uiSurfaceTooLarge(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth().testTag(A2uiTestTags.SURFACE_TOO_LARGE),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Text(
            text = "This surface is too large to display.",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * An opener for agent-supplied links on this button: it applies [LocalA2uiLinkPolicy] and, for
 * [A2uiLinkDecision.Confirm], shows [A2uiLinkConfirmDialog] before calling [LocalUriHandler].
 */
@Composable
internal fun rememberA2uiLinkOpener(): (A2uiLink) -> Unit {
    val uriHandler = LocalUriHandler.current
    val policy = LocalA2uiLinkPolicy.current
    var pending by remember { mutableStateOf<A2uiLink?>(null) }
    pending?.let { link ->
        A2uiLinkConfirmDialog(
            link = link,
            onConfirm = {
                pending = null
                runCatching { uriHandler.openUri(link.url) }
            },
            onDismiss = { pending = null },
        )
    }
    return { link -> dispatchA2uiLink(link, policy, uriHandler) { pending = it } }
}

/**
 * Routes [link] through [policy]: opens it, hands it to [confirm] for the person to approve, or
 * drops it. Returns whether the URI handler was called.
 */
internal fun dispatchA2uiLink(
    link: A2uiLink,
    policy: A2uiLinkPolicy,
    uriHandler: UriHandler,
    confirm: (A2uiLink) -> Unit,
): Boolean = when (policy.decide(link)) {
    A2uiLinkDecision.Open -> runCatching { uriHandler.openUri(link.url) }.isSuccess
    A2uiLinkDecision.Confirm -> {
        confirm(link)
        false
    }
    A2uiLinkDecision.Deny -> false
}

/** Asks the person before an agent-supplied link leaves the app; shows the full URL. */
@Composable
internal fun A2uiLinkConfirmDialog(link: A2uiLink, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(A2uiTestTags.LINK_CONFIRM),
        title = { Text("Open link?") },
        text = { Text(link.url) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Open") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
