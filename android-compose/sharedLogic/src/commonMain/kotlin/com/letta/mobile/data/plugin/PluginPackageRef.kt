package com.letta.mobile.data.plugin

/** A package by content (R3: installed by sha256): `sha256:<64 lowercase hex>`. */
data class PluginPackageRef(val sha256: String) {
    init {
        require(SHA256.matches(sha256)) { "a package sha256 is 64 lowercase hex characters" }
    }

    override fun toString(): String = "$PREFIX$sha256"

    companion object {
        const val PREFIX: String = "sha256:"
        private val SHA256 = Regex("^[0-9a-f]{64}$")

        /** `sha256:<hex>` as a ref, or null. */
        fun parse(text: String): PluginPackageRef? = if (text.startsWith(PREFIX)) of(text.removePrefix(PREFIX)) else null

        /** A bare sha256 as a ref, or null when it is not 64 lowercase hex characters. */
        fun of(sha256: String): PluginPackageRef? = if (SHA256.matches(sha256)) PluginPackageRef(sha256) else null
    }
}
