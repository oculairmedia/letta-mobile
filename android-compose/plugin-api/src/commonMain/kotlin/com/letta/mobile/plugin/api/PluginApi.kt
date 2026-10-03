package com.letta.mobile.plugin.api

/**
 * The version of this SPI (LCP v1, plan section 3.2). The module is semver'd: a minor adds members
 * with defaults (a plugin built against an older minor keeps loading), a major breaks them.
 *
 * A plugin's manifest names [CONTRACT_VERSION] as `contract.version`; the host supports a range of
 * majors and refuses a package outside it at install.
 */
public object PluginApi {
    /** The semantic version of `:plugin-api` (the published artifact's version). */
    public const val VERSION: String = "1.0.0"

    /** The contract major a plugin built against this SPI declares in `letta-plugin.json`. */
    public const val CONTRACT_VERSION: Int = 1
}
