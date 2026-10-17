package com.letta.mobile.data.shell

import com.letta.mobile.data.backend.AppServerRouteUnavailable

/**
 * letta-mobile-ckmub.17.5: how the shell's pages word a failure, on every client. A route the session
 * does not serve reads as that, never as a platform's repositories being missing.
 */
object ShellPageErrors {
    const val NOT_SERVED: String = "This backend does not serve this page yet."

    /** A failure as a page shows it: the not-served reason, else the failure's own message, else [fallback]. */
    fun message(throwable: Throwable, fallback: String): String = when (throwable) {
        is AppServerRouteUnavailable -> NOT_SERVED
        else -> throwable.message ?: throwable::class.simpleName ?: fallback
    }

    fun memory(throwable: Throwable): String = message(throwable, "Memory data could not be loaded.")

    fun tools(throwable: Throwable): String = message(throwable, "Tools could not be loaded.")

    fun isNotServed(throwable: Throwable): Boolean = throwable is AppServerRouteUnavailable
}
