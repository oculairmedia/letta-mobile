package com.letta.mobile.web.data

private val JsTypeSuffix = Regex("""\s+of type '[^']*'\s*$""")
private const val NON_KOTLIN_PREFIX = "Non-Kotlin exception "
private const val JS_ERROR_PREFIX = "Error: "

/**
 * letta-mobile-ckmub.8: a JavaScript error as a person reads it. Kotlin/Wasm reports a thrown JS
 * value as `Non-Kotlin exception Error: <message> of type 'class Error'`; only `<message>` belongs on
 * screen.
 */
internal fun cleanBrowserErrorMessage(raw: String): String =
    raw.trim()
        .removePrefix(NON_KOTLIN_PREFIX)
        .replace(JsTypeSuffix, "")
        .removePrefix(JS_ERROR_PREFIX)
        .trim()

/** This failure's message for the UI, cleaned of JS wrapping, or [fallback] when it has none. */
internal fun Throwable.displayMessage(fallback: String): String =
    message?.let(::cleanBrowserErrorMessage)?.takeIf { it.isNotBlank() } ?: fallback
