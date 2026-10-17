package com.letta.mobile.ui.chat.subagents

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.letta.mobile.data.subagents.SubagentDualViewState
import com.letta.mobile.data.subagents.SubagentView
import com.letta.mobile.data.subagents.SubagentViewKey
import com.letta.mobile.data.subagents.SubagentViewKind
import com.letta.mobile.sharedui.resources.Res
import com.letta.mobile.sharedui.resources.subagent_view_close_all
import com.letta.mobile.sharedui.resources.subagent_view_open_full
import com.letta.mobile.sharedui.resources.subagent_view_parent_tab
import com.letta.mobile.sharedui.resources.subagent_view_strip
import com.letta.mobile.sharedui.resources.subagent_view_tab_dream
import com.letta.mobile.sharedui.resources.subagent_view_tab_subagent
import com.letta.mobile.ui.chat.session.ChatSurfaceHost
import com.letta.mobile.ui.chat.session.SubagentViewSessions
import com.letta.mobile.ui.chat.surface.ChatSurfaceAppearance
import com.letta.mobile.ui.components.tabs.LettaTab
import com.letta.mobile.ui.components.tabs.LettaTabStrip
import com.letta.mobile.ui.components.tabs.LettaTabStripActions
import com.letta.mobile.ui.icons.LettaIcons
import com.letta.mobile.ui.theme.LettaDimens
import org.jetbrains.compose.resources.stringResource

/**
 * letta-mobile-zxk2n.8 / ulcg6.1: the subagent dual view of one parent conversation. A host keeps
 * one per parent conversation (a new parent starts closed) and binds every subagent opener to
 * [open]: the roster's "View conversation", a ring's, and the timeline's dispatch and report rows.
 */
@Stable
class SubagentDualViewHolder(initial: SubagentDualViewState = SubagentDualViewState.Closed) {
    var state: SubagentDualViewState by mutableStateOf(initial)
        private set

    fun open(view: SubagentView) {
        state = state.open(view)
    }

    fun select(key: SubagentViewKey) {
        state = state.select(key)
    }

    fun showParent() {
        state = state.showParent()
    }

    fun close(key: SubagentViewKey) {
        state = state.close(key)
    }

    fun closeAll() {
        state = SubagentDualViewState.Closed
    }
}

/** One holder per parent conversation: [parent] changing starts a closed dual view. */
@Composable
fun rememberSubagentDualViewHolder(parent: Any?): SubagentDualViewHolder = remember(parent) { SubagentDualViewHolder() }

/**
 * What a chat page binds the dual view with: its [holder], where a child conversation's session
 * comes from ([sessions]), and [onOpenFullView], the jump to the child's own full chat (null hides
 * it: a host that cannot open a hidden subagent agent's conversation as a page of its own).
 */
@Immutable
class SubagentDualViewBinding(
    val holder: SubagentDualViewHolder,
    val sessions: SubagentViewSessions,
    val onOpenFullView: ((SubagentView) -> Unit)? = null,
)

/**
 * How the subagent's pane is drawn: the parent page's [appearance] and [host] (names, agent links),
 * and [topInset], the host chrome floating over the top of the page (Android's header), which the
 * compact strip keeps below.
 */
@Immutable
data class SubagentDualViewOptions(
    val appearance: ChatSurfaceAppearance = ChatSurfaceAppearance(),
    val host: ChatSurfaceHost = ChatSurfaceHost(),
    val topInset: Dp = 0.dp,
)

/** Test tags of the dual view. */
object SubagentDualViewTags {
    const val FRAME = "subagent-dual-view"
    const val STRIP = "subagent-dual-view-strip"
    const val PARENT = "subagent-dual-view-parent"
    const val PANE = "subagent-dual-view-pane"
    const val BACK_TO_PARENT = "subagent-dual-view-back"
    const val OPEN_FULL = "subagent-dual-view-open-full"
    const val CLOSE_ALL = "subagent-dual-view-close-all"
    const val UNAVAILABLE = "subagent-dual-view-unavailable"
}

/**
 * Draws [parent] (the parent conversation's page) and, while a subagent view is open, the subagent's
 * conversation: beside it from [LettaDimens.Pane.dualViewBreakpoint] up, in its place below that,
 * with the strip switching between the two. Without a [binding], or with nothing open, it is just
 * the parent page. The parent stays in the same place in the tree, so opening and closing a
 * subagent never resets it.
 */
@Composable
fun SubagentDualViewFrame(
    binding: SubagentDualViewBinding?,
    modifier: Modifier = Modifier,
    options: SubagentDualViewOptions = SubagentDualViewOptions(),
    parent: @Composable (Modifier) -> Unit,
) {
    val state = binding?.holder?.state ?: SubagentDualViewState.Closed
    BoxWithConstraints(modifier.testTag(SubagentDualViewTags.FRAME)) {
        val layout = DualViewLayout.of(state, wide = maxWidth >= LettaDimens.Pane.dualViewBreakpoint)
        Column(Modifier.fillMaxSize()) {
            if (binding != null && layout.stripOnTop) {
                SubagentViewStrip(binding, layout, Modifier.padding(top = options.topInset))
            }
            Row(Modifier.fillMaxWidth().weight(1f)) {
                if (layout.showsParent) parent(Modifier.weight(1f).fillMaxHeight().testTag(SubagentDualViewTags.PARENT))
                if (binding != null) SubagentSide(binding, layout, options)
            }
        }
    }
}

/** Where the dual view's parts go for one state and window width. */
private data class DualViewLayout(val wide: Boolean, val open: Boolean, val showsSubagent: Boolean) {
    /** Compact: the strip leads the page so the reader can switch sides. */
    val stripOnTop: Boolean get() = open && !wide
    val showsParent: Boolean get() = wide || !showsSubagent
    val showsSubagentPane: Boolean get() = open && (wide || showsSubagent)

    companion object {
        fun of(state: SubagentDualViewState, wide: Boolean) =
            DualViewLayout(wide = wide, open = state.isOpen, showsSubagent = state.showsSubagent)
    }
}

/** The subagent's side: on a wide window its own strip over its pane, beside a divider. */
@Composable
private fun RowScope.SubagentSide(binding: SubagentDualViewBinding, layout: DualViewLayout, options: SubagentDualViewOptions) {
    val active = binding.holder.state.active ?: return
    if (!layout.showsSubagentPane) return
    if (layout.wide) VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(Modifier.weight(1f).fillMaxHeight()) {
        if (layout.wide) SubagentViewStrip(binding, layout)
        SubagentConversationPane(
            view = active,
            sessions = binding.sessions,
            options = options,
            modifier = Modifier.fillMaxSize().testTag(SubagentDualViewTags.PANE),
        )
    }
}

/**
 * The open subagent views as tabs (the shared [LettaTabStrip]), led on a compact window by the way
 * back to the parent, and ended by the jump to the child's full chat and closing the whole view.
 */
@Composable
private fun SubagentViewStrip(binding: SubagentDualViewBinding, layout: DualViewLayout, modifier: Modifier = Modifier) {
    val holder = binding.holder
    val state = holder.state
    val stripLabel = stringResource(Res.string.subagent_view_strip)
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(LettaDimens.Control.minTouchTarget)
                    .padding(horizontal = LettaDimens.Space.xs)
                    .semantics { contentDescription = stripLabel }
                    .testTag(SubagentDualViewTags.STRIP),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!layout.wide && state.showsSubagent) {
                    StripAction(LettaIcons.ArrowBack, stringResource(Res.string.subagent_view_parent_tab), SubagentDualViewTags.BACK_TO_PARENT) {
                        holder.showParent()
                    }
                }
                LettaTabStrip(
                    tabs = state.views.map { it.toTab() },
                    activeKey = state.active?.key?.value?.takeIf { layout.wide || state.showsSubagent },
                    actions = LettaTabStripActions(
                        onSelect = { key -> holder.select(SubagentViewKey(key)) },
                        onClose = { key -> holder.close(SubagentViewKey(key)) },
                    ),
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                StripTrailingActions(binding)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun StripTrailingActions(binding: SubagentDualViewBinding) {
    val active = binding.holder.state.active
    val openFull = binding.onOpenFullView
    if (openFull != null && active != null) {
        StripAction(LettaIcons.ExternalLink, stringResource(Res.string.subagent_view_open_full), SubagentDualViewTags.OPEN_FULL) {
            binding.holder.closeAll()
            openFull(active)
        }
    }
    StripAction(LettaIcons.Close, stringResource(Res.string.subagent_view_close_all), SubagentDualViewTags.CLOSE_ALL) {
        binding.holder.closeAll()
    }
}

@Composable
private fun StripAction(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(LettaDimens.Control.minTouchTarget).testTag(tag)) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(LettaDimens.Control.icon))
    }
}

/** A view's tab: its dispatch's description, else "Subagent · 1a2b3c4d" (or "Dream · ..."). */
@Composable
private fun SubagentView.toTab(): LettaTab = LettaTab(key = key.value, title = title ?: fallbackTitle())

@Composable
private fun SubagentView.fallbackTitle(): String = when (kind) {
    SubagentViewKind.SUBAGENT -> stringResource(Res.string.subagent_view_tab_subagent, shortAgentId)
    SubagentViewKind.DREAM -> stringResource(Res.string.subagent_view_tab_dream, shortAgentId)
}
