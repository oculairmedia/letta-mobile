package com.letta.mobile.desktop.phone

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.ui.chat.AgentIdentity
import com.letta.mobile.ui.chat.AgentIdentityPill
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens

/** Test tags for the phone shell's own chrome. */
internal object PhoneShellTags {
    const val CHAT_HEADER = "phone-chat-header"
    const val CANVAS_IDENTITY_PILL = "phone-canvas-identity-pill"
    const val TOP_BAR = "phone-top-bar"
    const val DRAWER = "phone-drawer"
}

/**
 * The chat screen's floating header on a phone (Android's ChatScreen header pills): the menu that
 * opens the agents panel, the shared [AgentIdentityPill] (letta-mobile-vgouv: the one pill Android
 * draws), and the way back to the canvas. It floats over the full-screen page, which draws edge to
 * edge under it; its measured height (plus the status bar) is the page's `topChromeInset`.
 */
@Composable
internal fun PhoneChatHeader(
    identity: AgentIdentity,
    onMenu: () -> Unit,
    onCanvas: (() -> Unit)?,
    onHeightChange: (Dp) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    Row(
        modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.sm)
            .onSizeChanged { onHeightChange(with(density) { it.height.toDp() }) }
            .testTag(PhoneShellTags.CHAT_HEADER),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderPill { IconButton(onClick = onMenu) { Icon(LettaIcons.Menu, contentDescription = "Agents and conversations") } }
        Spacer(Modifier.size(LettaDimens.Space.sm))
        AgentIdentityPill(identity, Modifier.weight(1f, fill = false))
        Spacer(Modifier.weight(1f))
        if (onCanvas != null) {
            HeaderPill { IconButton(onClick = onCanvas) { Icon(LettaIcons.Dashboard, contentDescription = "Canvas") } }
        }
    }
}

/**
 * The agent pill alone, where the header draws it, while the phone's canvas mode keeps the top of
 * the board clear (Android's canvas identity pill): under the status bar, at the header's start
 * inset. Only the pill takes touches.
 */
@Composable
internal fun PhoneCanvasIdentityPill(identity: AgentIdentity, modifier: Modifier = Modifier) {
    Row(
        modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = LettaDimens.Space.md, vertical = LettaDimens.Space.sm)
            .testTag(PhoneShellTags.CANVAS_IDENTITY_PILL),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AgentIdentityPill(identity)
    }
}

@Composable
private fun HeaderPill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = HEADER_ELEVATION,
        shadowElevation = HEADER_ELEVATION,
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * The phone's app bar for the destinations that are not the chat (Home, Settings, Memory...): the
 * menu that opens the agents panel and the destination's title, under the status bar. [content] sits
 * below it and above the gesture bar.
 */
@Composable
internal fun PhoneDestinationFrame(
    title: String,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    Column(modifier.background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .windowInsetsPadding(WindowInsets.statusBars)
                .height(TOP_BAR_HEIGHT)
                .padding(horizontal = LettaDimens.Space.xs)
                .testTag(PhoneShellTags.TOP_BAR),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onMenu) { Icon(LettaIcons.Menu, contentDescription = "Agents and conversations") }
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        content(Modifier.fillMaxWidth().weight(1f).windowInsetsPadding(WindowInsets.navigationBars))
    }
}

/**
 * The app's main content pane as the [phone] shows it: [bare] (the chat page, which draws edge to
 * edge with its own floating header) or in a [PhoneDestinationFrame]. Without a phone it is exactly
 * `content(modifier)`, so the desktop layout does not change.
 */
@Composable
internal fun PhoneAwareContent(
    phone: DesktopPhoneChrome?,
    title: String,
    bare: Boolean,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    if (phone == null || bare) {
        content(modifier)
    } else {
        PhoneDestinationFrame(title, onMenu = { phone.drawerOpen = true }, modifier = modifier, content = content)
    }
}

/**
 * The agents and conversations panel (desktop's rail and sidebar) as a phone's navigation drawer:
 * over the screen from the start edge, on a scrim that closes it.
 */
@Composable
internal fun PhoneNavigationDrawer(
    open: Boolean,
    onDismiss: () -> Unit,
    reducedMotion: Boolean,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(open, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            )
        }
        AnimatedVisibility(
            open,
            enter = if (reducedMotion) fadeIn() else slideInHorizontally { -it },
            exit = if (reducedMotion) fadeOut() else slideOutHorizontally { -it },
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxHeight()
                    .widthIn(max = DRAWER_MAX_WIDTH)
                    .testTag(PhoneShellTags.DRAWER),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = DRAWER_ELEVATION,
            ) {
                Row(
                    Modifier
                        .fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .windowInsetsPadding(WindowInsets.navigationBars),
                ) { content() }
            }
        }
    }
}

private val HEADER_ELEVATION: Dp = 3.dp
private val TOP_BAR_HEIGHT: Dp = 56.dp
private val DRAWER_MAX_WIDTH: Dp = 360.dp
private val DRAWER_ELEVATION: Dp = 1.dp
private const val SCRIM_ALPHA = 0.32f
