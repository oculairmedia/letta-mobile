package com.letta.mobile.ui.shell.pages.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import com.composables.icons.lucide.ArrowUp
import com.composables.icons.lucide.Lucide
import com.letta.mobile.ui.components.LettaInlineError
import com.letta.mobile.ui.components.foundation.LettaIcon
import com.letta.mobile.ui.components.foundation.LettaSearchField
import com.letta.mobile.ui.components.foundation.LettaSearchOptions
import com.letta.mobile.ui.shell.tokens.ShellDimens
import com.letta.mobile.ui.theme.LettaDimens

@Composable
internal fun HomeHeader(page: HomePageScope) {
    Column(verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.md)) {
        Row(horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(LettaDimens.Space.xs)) {
                if (page.options.showTitle) {
                    Text(PAGE_TITLE, style = MaterialTheme.typography.headlineSmall)
                }
            }
            if (page.wide && page.options.showSearch) HomeSearchField(page, Modifier.width(ShellDimens.pageSearchWidth))
            page.options.headerActions?.invoke(this)
        }
        if (!page.wide && page.options.showSearch) HomeSearchField(page, Modifier.fillMaxWidth())
        // A backend figure failed to load; the rest of the page still works, so this is a note, not a wall.
        page.state.stats.error?.let { message ->
            LettaInlineError(
                message,
                Modifier.testTag(HomePageTags.ERROR),
                title = null,
                onRetry = page.actions::refresh,
                retrying = page.state.stats.loading,
            )
        }
    }
}

@Composable
private fun HomeSearchField(page: HomePageScope, modifier: Modifier) {
    LettaSearchField(
        query = page.state.search.query,
        onQueryChange = page.actions::updateSearchQuery,
        modifier = modifier.testTag(HomePageTags.SEARCH),
        options = LettaSearchOptions(placeholder = HomeSearchPlaceholder, onClear = page.actions::clearSearch),
    )
}

/**
 * Home's chatbox. Deliberately not a second ComposerBar: it owns one draft and hands it to the host,
 * which routes it into the real chat pipeline. Attachments, mentions and slash commands stay the chat
 * page's job - this is the "just say the thing" entry point. Enter sends, Shift+Enter breaks a line.
 */
@Composable
internal fun HomeComposer(page: HomePageScope, modifier: Modifier = Modifier) {
    var draft by remember { mutableStateOf("") }
    val canSend = draft.isNotBlank()
    val submit = {
        if (draft.isNotBlank()) {
            page.navigation.onSubmitPrompt(draft.trim())
            draft = ""
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = LettaDimens.Space.lg, vertical = LettaDimens.Space.md),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(LettaDimens.Space.md),
        ) {
            val field = ComposerField(draft, page.options.composerPlaceholder, { draft = it }, submit)
            HomeComposerField(field, Modifier.weight(1f))
            HomeSendButton(canSend = canSend, onSend = submit)
        }
    }
}

/** The composer field's draft, its placeholder and what typing and sending do. */
private class ComposerField(
    val text: String,
    val placeholder: String,
    val onTextChanged: (String) -> Unit,
    val onSubmit: () -> Unit,
)

@Composable
private fun HomeComposerField(field: ComposerField, modifier: Modifier = Modifier) {
    val text = field.text
    val onSubmit = field.onSubmit
    val textStyle = MaterialTheme.typography.bodyLarge.merge(TextStyle(color = MaterialTheme.colorScheme.onSurface))
    BasicTextField(
        value = text,
        onValueChange = field.onTextChanged,
        modifier = modifier
            .heightIn(min = LettaDimens.Control.fieldHeight, max = ShellDimens.homeComposerMaxHeight)
            .padding(vertical = LettaDimens.Space.xs)
            .testTag(HomePageTags.COMPOSER)
            .onPreviewKeyEvent { event ->
                val isSend = event.type == KeyEventType.KeyDown &&
                    (event.key == Key.Enter || event.key == Key.NumPadEnter) &&
                    !event.isShiftPressed
                if (isSend) onSubmit()
                isSend
            },
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        maxLines = COMPOSER_MAX_LINES,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSubmit() }),
        decorationBox = { innerTextField ->
            Box(Modifier.fillMaxWidth()) {
                if (text.isEmpty()) Text(field.placeholder, style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                innerTextField()
            }
        },
    )
}

@Composable
private fun HomeSendButton(canSend: Boolean, onSend: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onSend,
        enabled = canSend,
        modifier = Modifier.size(LettaDimens.Control.iconButtonLg).testTag(HomePageTags.SEND),
        shape = CircleShape,
        color = if (canSend) colors.primary else colors.surfaceContainerHigh,
        contentColor = if (canSend) colors.onPrimary else colors.onSurfaceVariant.copy(alpha = LettaDimens.Alpha.disabled),
    ) {
        Box(contentAlignment = Alignment.Center) {
            LettaIcon(Lucide.ArrowUp, contentDescription = SEND_LABEL)
        }
    }
}

private const val PAGE_TITLE = "Home"
/** What Home searches, for a host field (desktop's header) that searches the page in its place. */
const val HomeSearchPlaceholder: String = "Search agents, tools, blocks and messages"

private const val SEND_LABEL = "Send message"
internal const val NO_CONVERSATIONS = "No conversations yet. Start one from the chatbox."
private const val COMPOSER_MAX_LINES = 5
