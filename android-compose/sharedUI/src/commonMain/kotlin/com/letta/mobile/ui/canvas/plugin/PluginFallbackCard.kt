package com.letta.mobile.ui.canvas.plugin

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.composables.icons.lucide.CircleArrowUp
import com.composables.icons.lucide.Cpu
import com.composables.icons.lucide.ExternalLink
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.PackageX
import com.composables.icons.lucide.PenTool
import com.composables.icons.lucide.Puzzle
import com.composables.icons.lucide.TriangleAlert
import com.composables.icons.lucide.WifiOff
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.canvas_plugin_badge_fault
import com.letta.mobile.sharedui.resources.canvas_plugin_badge_newer_version
import com.letta.mobile.sharedui.resources.canvas_plugin_badge_not_installed
import com.letta.mobile.sharedui.resources.canvas_plugin_badge_offline
import com.letta.mobile.sharedui.resources.canvas_plugin_card_description
import com.letta.mobile.sharedui.resources.canvas_plugin_open
import com.letta.mobile.sharedui.resources.canvas_plugin_open_action
import com.letta.mobile.sharedui.resources.canvas_plugin_snapshot_description
import com.letta.mobile.sharedui.resources.canvas_plugin_snapshot_loading
import com.letta.mobile.sharedui.resources.canvas_plugin_status_done
import com.letta.mobile.sharedui.resources.canvas_plugin_status_failed
import com.letta.mobile.sharedui.resources.canvas_plugin_status_idle
import com.letta.mobile.sharedui.resources.canvas_plugin_status_running
import com.letta.mobile.sharedui.resources.canvas_plugin_untitled
import com.letta.mobile.ui.canvas.LocalCanvasCompact
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import com.composables.icons.lucide.Box as LucideBox
import com.composables.icons.lucide.File as LucideFile
import com.composables.icons.lucide.Image as LucideImage
import com.composables.icons.lucide.Link as LucideLink

/**
 * The card every client draws for a plugin element it has no live renderer for (letta-mobile-s416w.4,
 * plan section 4): no plugin code, so it works offline and with the plugin uninstalled.
 *
 * From the top: a handle bar with the element's icon and title (a drag there moves the element,
 * through [PluginElementChrome.moveHandle]); the snapshot, fitted inside the frame, a skeleton while
 * its bytes are on their way, or the icon when there is none; then the subtitle, a status strip when
 * the props carry the generic `status`/`progress`, the badges, and Open for `fallback.openUrl`.
 *
 * Drawn inside the element's frame in world units, like a note card, so it is the same proportion
 * of the element on every display. Touch (a phone's board) gets a taller handle bar and Open button.
 */
@Composable
fun PluginFallbackCard(view: PluginElementView, chrome: PluginElementChrome, modifier: Modifier = Modifier) {
    val touch = LocalCanvasCompact.current
    val id = view.element.id
    val title = view.title ?: stringResource(Res.string.canvas_plugin_untitled)
    val description = stringResource(Res.string.canvas_plugin_card_description, title)
    val openLabel = stringResource(Res.string.canvas_plugin_open_action, title)
    val open = chrome.open
    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag(CanvasPluginTestTags.card(id))
            .semantics {
                contentDescription = description
                if (open != null) customActions = listOf(CustomAccessibilityAction(openLabel) { open(); true })
            },
        shape = RoundedCornerShape(LettaDimens.Radius.md),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(LettaDimens.Stroke.hairline, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = LettaDimens.Space.xs,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            PluginCardHeader(title = title, icon = pluginIcon(view.element.fallback.icon), handle = chrome.moveHandle, touch = touch)
            PluginSnapshotArea(view = view, title = title, modifier = Modifier.weight(1f).fillMaxWidth())
            PluginCardFooter(view = view, open = open, touch = touch)
        }
    }
}

/** The closed icon set a fallback card names (`fallback.icon`); anything else is the puzzle piece. */
internal fun pluginIcon(name: String?): ImageVector = when (name?.trim()?.lowercase()) {
    "image" -> Lucide.LucideImage
    "file" -> Lucide.LucideFile
    "link" -> Lucide.LucideLink
    "box" -> Lucide.LucideBox
    "cpu" -> Lucide.Cpu
    "pen-tool" -> Lucide.PenTool
    else -> Lucide.Puzzle
}

/** The badges a card wears, in the order it wears them. */
enum class PluginCardBadge { NOT_INSTALLED, NEWER_VERSION, OFFLINE, FAULT }

/** Which badges [view] wears. */
internal fun badgesOf(view: PluginElementView): List<PluginCardBadge> = buildList {
    when (view.availability.install) {
        PluginInstallState.NOT_INSTALLED -> add(PluginCardBadge.NOT_INSTALLED)
        PluginInstallState.NEWER_VERSION -> add(PluginCardBadge.NEWER_VERSION)
        PluginInstallState.UNKNOWN, PluginInstallState.INSTALLED -> Unit
    }
    if (view.availability.offline) add(PluginCardBadge.OFFLINE)
    if (view.faulted) add(PluginCardBadge.FAULT)
}

@Composable
internal fun PluginCardHeader(title: String, icon: ImageVector, handle: Modifier, touch: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (touch) LettaDimens.Control.iconButtonLg else LettaDimens.Control.iconButton)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .then(handle)
            .padding(horizontal = LettaDimens.Space.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(LettaDimens.Control.icon),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.size(LettaDimens.Space.sm))
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PluginSnapshotArea(view: PluginElementView, title: String, modifier: Modifier) {
    val id = view.element.id
    when (val snapshot = view.snapshot) {
        is PluginSnapshotImage.Ready -> Image(
            bitmap = snapshot.bitmap,
            contentDescription = stringResource(Res.string.canvas_plugin_snapshot_description, title),
            contentScale = ContentScale.Fit,
            modifier = modifier.padding(LettaDimens.Space.xs).testTag(CanvasPluginTestTags.snapshot(id)),
        )
        PluginSnapshotImage.Loading -> {
            val loading = stringResource(Res.string.canvas_plugin_snapshot_loading, title)
            Box(
                modifier = modifier
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .testTag(CanvasPluginTestTags.skeleton(id))
                    .semantics { contentDescription = loading },
            )
        }
        PluginSnapshotImage.None, PluginSnapshotImage.Unreadable -> Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainer).testTag(CanvasPluginTestTags.placeholder(id)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = pluginIcon(view.element.fallback.icon),
                contentDescription = null,
                modifier = Modifier.size(LettaDimens.Control.actionButton),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PluginCardFooter(view: PluginElementView, open: (() -> Unit)?, touch: Boolean) {
    val subtitle = view.element.fallback.subtitle?.trim()?.takeIf { it.isNotEmpty() }
    val status = view.status
    val badges = badgesOf(view)
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = LettaDimens.Space.sm, vertical = LettaDimens.Space.xs),
        verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs),
    ) {
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (status != null) PluginStatusStrip(status = status, modifier = Modifier.testTag(CanvasPluginTestTags.status(view.element.id)))
        if (badges.isNotEmpty() || open != null) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
                    badges.forEach { badge -> PluginBadge(badge, Modifier.testTag(CanvasPluginTestTags.badge(view.element.id, badge))) }
                }
                if (open != null) PluginOpenButton(open = open, touch = touch, modifier = Modifier.testTag(CanvasPluginTestTags.open(view.element.id)))
            }
        }
    }
}

/** The generic status strip: the status word and, when there is one, the progress. */
@Composable
private fun PluginStatusStrip(status: PluginElementStatus, modifier: Modifier) {
    val color = toneColor(status.tone)
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.sm),
    ) {
        statusLabel(status)?.let { label ->
            Text(text = label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
        }
        status.progress?.let { progress ->
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.weight(1f).height(LettaDimens.Space.xs),
                color = color,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            )
        }
    }
}

@Composable
private fun statusLabel(status: PluginElementStatus): String? = when (status.tone) {
    PluginStatusTone.IDLE -> stringResource(Res.string.canvas_plugin_status_idle)
    PluginStatusTone.RUNNING -> stringResource(Res.string.canvas_plugin_status_running)
    PluginStatusTone.DONE -> stringResource(Res.string.canvas_plugin_status_done)
    PluginStatusTone.FAILED -> stringResource(Res.string.canvas_plugin_status_failed)
    PluginStatusTone.OTHER -> status.status
}

@Composable
private fun toneColor(tone: PluginStatusTone): Color = when (tone) {
    PluginStatusTone.RUNNING -> MaterialTheme.colorScheme.primary
    PluginStatusTone.DONE -> MaterialTheme.colorScheme.tertiary
    PluginStatusTone.FAILED -> MaterialTheme.colorScheme.error
    PluginStatusTone.IDLE, PluginStatusTone.OTHER -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun PluginBadge(badge: PluginCardBadge, modifier: Modifier) {
    val (label, icon) = badgeContent(badge)
    val fault = badge == PluginCardBadge.FAULT
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(LettaDimens.Radius.sm),
        color = if (fault) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (fault) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = LettaDimens.Space.xs, vertical = LettaDimens.Space.hair),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.hair),
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.iconSm))
            Text(text = stringResource(label), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

private fun badgeContent(badge: PluginCardBadge): Pair<StringResource, ImageVector> = when (badge) {
    PluginCardBadge.NOT_INSTALLED -> Res.string.canvas_plugin_badge_not_installed to Lucide.PackageX
    PluginCardBadge.NEWER_VERSION -> Res.string.canvas_plugin_badge_newer_version to Lucide.CircleArrowUp
    PluginCardBadge.OFFLINE -> Res.string.canvas_plugin_badge_offline to Lucide.WifiOff
    PluginCardBadge.FAULT -> Res.string.canvas_plugin_badge_fault to Lucide.TriangleAlert
}

@Composable
private fun PluginOpenButton(open: () -> Unit, touch: Boolean, modifier: Modifier) {
    val padding = if (touch) LettaDimens.Space.md else LettaDimens.Space.sm
    TextButton(onClick = open, modifier = modifier, contentPadding = PaddingValues(horizontal = padding)) {
        Icon(imageVector = Lucide.ExternalLink, contentDescription = null, modifier = Modifier.size(LettaDimens.Control.iconSm))
        Spacer(modifier = Modifier.size(LettaDimens.Space.xs))
        Text(text = stringResource(Res.string.canvas_plugin_open), style = MaterialTheme.typography.labelLarge)
    }
}
