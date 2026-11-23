package com.letta.mobile.ui.shell.pages.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.letta.mobile.ui.components.LettaInlineError
import com.letta.mobile.ui.components.LettaPageHeader
import com.letta.mobile.ui.components.foundation.LettaSearchField
import com.letta.mobile.ui.components.foundation.LettaSearchOptions
import com.letta.mobile.ui.shell.tokens.ShellDimens

/**
 * Home's header on the one page header every shared page draws ([LettaPageHeader]: the same title
 * style as Schedules, Memory and the rest, dropped where the window header already names the page),
 * with the search beside the title when wide and under it on a phone, and a backend error under that.
 */
@Composable
internal fun HomeHeader(page: HomePageScope) {
    val search: (@Composable () -> Unit)? = if (page.options.showSearch) {
        { HomeSearchField(page, if (page.wide) Modifier.width(ShellDimens.pageSearchWidth) else Modifier.fillMaxWidth()) }
    } else {
        null
    }
    LettaPageHeader(
        title = HOME_PAGE_NAME.takeIf { page.showsTitle },
        context = search,
        actions = page.options.headerActions,
    ) {
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
 * Home's prompt bar: the host's [HomePageOptions.promptBar] (the shared chat composer, the same bar
 * and Send as the chat page). It owns one draft and hands it to the host, which routes it into the
 * real chat pipeline; attachments, mentions and slash commands stay the chat page's job.
 */
@Composable
internal fun HomePromptBar(page: HomePageScope, modifier: Modifier = Modifier) {
    val bar = page.options.promptBar ?: return
    bar(page.options.composerPlaceholder, page.navigation.onSubmitPrompt, modifier.fillMaxWidth().testTag(HomePageTags.COMPOSER))
}

/** The page's name: its heading when it has no greeting, else its pane title for assistive tech. */
internal const val HOME_PAGE_NAME = "Home"
/** What Home searches, for a host field (desktop's header) that searches the page in its place. */
const val HomeSearchPlaceholder: String = "Search agents, tools, blocks and messages"

internal const val NO_CONVERSATIONS = "No conversations yet. Start one from the chatbox."
