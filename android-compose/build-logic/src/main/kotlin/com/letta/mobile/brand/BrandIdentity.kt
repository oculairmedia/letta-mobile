package com.letta.mobile.brand

import java.io.StringReader
import java.util.Properties

/**
 * The product's display identity, read from `branding/brand.properties`. Modules get it as the
 * `brand` extension (see [BrandPlugin]) and drive every display surface from it: launcher labels,
 * installer names and icons, the web title and manifest, and the generated runtime `Brand` object.
 *
 * Paths are relative to the root project directory (`android-compose/`).
 */
data class BrandIdentity(
    val name: String,
    val labelSuffixes: Map<String, String>,
    val iconSvg: String,
    val iconPng: String,
    val iconIco: String,
    val iconIcns: String,
    val androidRes: String,
    val webIcons: String,
) {
    init {
        require(name.isNotBlank()) { "brand.name must not be blank" }
        require(name.none { it == '"' || it == '\\' || it == '$' || it == '<' || it == '&' }) {
            "brand.name must be plain text (no quotes, backslashes, \$, < or &): $name"
        }
    }

    /**
     * The Android launcher label for one variant: the name, plus the suffix configured for the
     * variant itself (`brand.label.sideloadDebug`) or, failing that, for its build type
     * (`brand.label.debug`).
     */
    fun androidLabel(variantName: String, buildType: String?): String {
        val suffix = labelSuffixes[variantName] ?: buildType?.let(labelSuffixes::get)
        return if (suffix.isNullOrBlank()) name else "$name $suffix"
    }

    /** Replaces the brand tokens ([NAME_TOKEN]) in a resource processed at build time. */
    fun expand(text: String): String = text.replace(NAME_TOKEN, name)

    /** The Kotlin source of the runtime `Brand` object generated into [packageName]. */
    fun kotlinSource(packageName: String): String = """
        |// Generated from branding/brand.properties by the com.letta.mobile.brand plugin. Do not edit.
        |package $packageName
        |
        |/** The product's display identity, generated at build time from branding/brand.properties. */
        |public object Brand {
        |    /** The product name shown in window titles, the shell header and notifications. */
        |    public const val NAME: String = "$name"
        |}
        |
    """.trimMargin()

    companion object {
        /** The token a processed resource (index.html, the web manifest) writes for the product name. */
        const val NAME_TOKEN = "@BRAND_NAME@"

        private const val LABEL_PREFIX = "brand.label."

        fun parse(text: String): BrandIdentity {
            val properties = Properties().apply { load(StringReader(text)) }
            fun required(key: String): String =
                requireNotNull(properties.getProperty(key)?.trim()?.takeIf(String::isNotEmpty)) {
                    "branding/brand.properties is missing $key"
                }
            val suffixes = properties.stringPropertyNames()
                .filter { it.startsWith(LABEL_PREFIX) }
                .associate { it.removePrefix(LABEL_PREFIX) to properties.getProperty(it).trim() }
            return BrandIdentity(
                name = required("brand.name"),
                labelSuffixes = suffixes,
                iconSvg = required("brand.icon.svg"),
                iconPng = required("brand.icon.png"),
                iconIco = required("brand.icon.ico"),
                iconIcns = required("brand.icon.icns"),
                androidRes = required("brand.android.res"),
                webIcons = required("brand.web.icons"),
            )
        }
    }
}
