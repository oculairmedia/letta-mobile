package com.letta.mobile.ui.chat.session

/*
 * letta-mobile-by4vm.98: the page-error rule both session ports share (the timeline port and
 * desktop's): the snackbar shows an owner's error once, while the owner keeps it until the next
 * send so the ambient glow (`runFailed`) still reads "failed".
 */

/** The page showed [error]: it is not shown again. */
fun TimelineLocalState.acknowledge(error: String?): TimelineLocalState =
    if (error == null) this else copy(acknowledgedError = error)

/** [error] as the page shows it: hidden once acknowledged. */
fun TimelineLocalState.pageError(error: String?): String? = error.takeUnless { it == acknowledgedError }

/** Once the owner's error is gone (a send cleared it), the same message later is a new error. */
fun TimelineLocalState.forgetAcknowledgedOnceCleared(error: String?): TimelineLocalState =
    if (error == null && acknowledgedError != null) copy(acknowledgedError = null) else this
