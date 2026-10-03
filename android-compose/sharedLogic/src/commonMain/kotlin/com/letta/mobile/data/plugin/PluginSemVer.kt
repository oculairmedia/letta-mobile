package com.letta.mobile.data.plugin

/**
 * A plugin version (semver 2.0.0, the manifest's `version`), ordered by precedence: build metadata
 * is ignored, a pre-release sorts before its release, pre-release identifiers compare numerically
 * when both are numbers and as text otherwise.
 */
data class PluginSemVer(val major: Int, val minor: Int, val patch: Int, val preRelease: List<String> = emptyList()) : Comparable<PluginSemVer> {
    override fun compareTo(other: PluginSemVer): Int {
        val core = compareValuesBy(this, other, PluginSemVer::major, PluginSemVer::minor, PluginSemVer::patch)
        return if (core != 0) core else comparePreRelease(preRelease, other.preRelease)
    }

    companion object {
        private val PATTERN = Regex(PluginManifestSchema.SEMVER_PATTERN)

        fun parse(text: String): PluginSemVer? {
            val match = PATTERN.matchEntire(text) ?: return null
            val (major, minor, patch) = match.destructured
            val pre = match.groupValues[4].removePrefix("-").takeIf { it.isNotEmpty() }?.split('.').orEmpty()
            return PluginSemVer(major.toIntOrNull() ?: return null, minor.toIntOrNull() ?: return null, patch.toIntOrNull() ?: return null, pre)
        }

        private fun comparePreRelease(a: List<String>, b: List<String>): Int = when {
            a.isEmpty() || b.isEmpty() -> b.size.coerceAtMost(1) - a.size.coerceAtMost(1)
            else -> a.zip(b).map { (x, y) -> compareIdentifier(x, y) }.firstOrNull { it != 0 } ?: a.size.compareTo(b.size)
        }

        private fun compareIdentifier(a: String, b: String): Int {
            val x = a.toLongOrNull()
            val y = b.toLongOrNull()
            return when {
                x != null && y != null -> x.compareTo(y)
                x != null -> -1
                y != null -> 1
                else -> a.compareTo(b)
            }
        }
    }
}
