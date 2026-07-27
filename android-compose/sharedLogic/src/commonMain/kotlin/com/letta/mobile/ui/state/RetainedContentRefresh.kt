package com.letta.mobile.ui.state

/**
 * Pure policy for screens that keep successfully loaded content visible while refreshing.
 *
 * A caller owns the monotonically increasing request ID and maps these outcomes onto its
 * platform-specific UI state container.
 */
object RetainedContentRefresh {
    sealed interface Start<out Content> {
        data class Loading(val requestId: Long) : Start<Nothing>

        data class Retaining<Content>(
            val requestId: Long,
            val content: Content,
        ) : Start<Content>

        data object Skip : Start<Nothing>
    }

    sealed interface Failure<out Content> {
        data class ShowError(val message: String) : Failure<Nothing>

        data class Retain<Content>(
            val content: Content,
            val message: String,
        ) : Failure<Content>
    }

    fun nextRequestId(previousRequestId: Long): Long = previousRequestId + 1

    fun <Content> begin(
        requestId: Long,
        retainedContent: Content?,
        canRefresh: (Content) -> Boolean = { true },
    ): Start<Content> = when {
        retainedContent == null -> Start.Loading(requestId)
        !canRefresh(retainedContent) -> Start.Skip
        else -> Start.Retaining(requestId, retainedContent)
    }

    fun isCurrent(requestId: Long, latestRequestId: Long): Boolean = requestId == latestRequestId

    fun <Content> failure(retainedContent: Content?, message: String): Failure<Content> =
        retainedContent?.let { Failure.Retain(it, message) } ?: Failure.ShowError(message)
}
