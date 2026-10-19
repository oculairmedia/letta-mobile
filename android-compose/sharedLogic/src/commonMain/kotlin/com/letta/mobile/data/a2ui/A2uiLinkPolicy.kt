package com.letta.mobile.data.a2ui

import com.letta.mobile.data.plugin.view.ViewLink

/**
 * A URL an A2UI `openUrl` call asked to open. Only `http`, `https` and
 * `mailto` parse; `javascript:`, `file:`, `intent:`, `content:` and every other scheme never reach
 * the platform URI handler. http(s) reuses the plugin bridge's [ViewLink] shape, so userinfo
 * (`https://bank.com@evil.example`) and whitespace are refused the same way for both.
 */
data class A2uiLink(val url: String, val scheme: String) {
    companion object {
        private val MAILTO = Regex("^mailto:[^\\s/][^\\s]*$", RegexOption.IGNORE_CASE)

        fun parse(raw: String): A2uiLink? {
            val url = raw.trim()
            if (MAILTO.matches(url)) return A2uiLink(url, "mailto")
            val web = ViewLink.parse(url) ?: return null
            return A2uiLink(url, web.scheme)
        }
    }
}

enum class A2uiLinkDecision {
    /** Open without asking (a host that already trusts the destination). */
    Open,

    /** Show the URL and open it only when the person confirms. */
    Confirm,

    /** Never open. */
    Deny,
}

/** What a host does with a parsed [A2uiLink]; the renderer only opens links this policy allows. */
fun interface A2uiLinkPolicy {
    fun decide(link: A2uiLink): A2uiLinkDecision

    companion object {
        /** The default: every link is shown to the person before it opens. */
        val ConfirmAll: A2uiLinkPolicy = A2uiLinkPolicy { A2uiLinkDecision.Confirm }

        val DenyAll: A2uiLinkPolicy = A2uiLinkPolicy { A2uiLinkDecision.Deny }
    }
}
