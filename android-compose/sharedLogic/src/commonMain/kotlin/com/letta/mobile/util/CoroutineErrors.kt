package com.letta.mobile.util

import kotlinx.coroutines.CancellationException

/**
 * Like [runCatching], but rethrows [CancellationException] so structured
 * cancellation is not turned into `Result.failure`.
 *
 * Prefer this whenever [block] suspends (or calls into code that may).
 */
suspend inline fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        Result.failure(error)
    }
