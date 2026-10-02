package com.letta.mobile.data.plugin

/**
 * A network origin a plugin may reach (plan section 3.3, `net.connect`, and a page's CSP domains):
 * scheme, host and an optional port, nothing else. No paths, no queries, no userinfo, and no
 * wildcard except a leading `*.` under a host of two or more labels (`*.example.test`).
 */
data class PluginOrigin(val scheme: String, val host: String, val port: Int?) {
    val isWildcard: Boolean get() = host.startsWith(WILDCARD)

    override fun toString(): String = "$scheme://$host" + (port?.let { ":$it" } ?: "")

    companion object {
        private const val WILDCARD = "*."
        private val SCHEMES = setOf("http", "https", "ws", "wss")
        private val SHAPE = Regex("^([a-z]+)://(\\*\\.)?([a-z0-9-]+(\\.[a-z0-9-]+)*|\\[[0-9a-f:.]+\\])(:([0-9]{1,5}))?$")

        /** [text] as an origin, or null when it is anything more or less than one. */
        fun parse(text: String): PluginOrigin? {
            val match = SHAPE.matchEntire(text) ?: return null
            val (scheme, wildcard, host) = match.destructured
            val port = match.groupValues[6].takeIf { it.isNotEmpty() }?.toInt()
            return PluginOrigin(scheme, wildcard + host, port).takeIf { it.isAllowed() }
        }

        private val PORTS = 1..65535

        private fun PluginOrigin.isAllowed(): Boolean = scheme in SCHEMES && portAllowed() && wildcardAllowed()

        private fun PluginOrigin.portAllowed(): Boolean = port?.let { it in PORTS } ?: true

        /** A wildcard covers subdomains of a host of two labels or more: `*.example.test`, never `*.test`. */
        private fun PluginOrigin.wildcardAllowed(): Boolean = !isWildcard || host.removePrefix(WILDCARD).contains('.')
    }
}

/**
 * Paths inside a package (plan section 3.1: every page's `html` and the runtime's files are inside
 * the package): relative, `/`-separated, with no `..`, no `.`, no empty segment, no backslash, no
 * drive or scheme, no control character.
 */
object PluginPackagePaths {
    /** The manifest's place in every package. */
    const val MANIFEST: String = "letta-plugin.json"

    /** Where pages live in a package. */
    const val PAGES_DIR: String = "pages/"

    /** Whether [path] stays inside the package it is resolved against. */
    fun isInside(path: String): Boolean {
        if (path.isEmpty() || path.startsWith("/")) return false
        return path.none(::isForbidden) && path.trimEnd('/').split('/').none { it in UNSAFE_SEGMENTS }
    }

    private fun isForbidden(char: Char): Boolean = char in FORBIDDEN || char.code < FIRST_PRINTABLE

    private const val FORBIDDEN = "\\:"
    private const val FIRST_PRINTABLE = 0x20
    private val UNSAFE_SEGMENTS = setOf("", ".", "..")

    /** Whether [path] is a page: an `.html` file under `pages/`, inside the package. */
    fun isPage(path: String): Boolean = isInside(path) && PAGE.matches(path)

    private val PAGE = Regex("^pages/.+\\.html$")
}
