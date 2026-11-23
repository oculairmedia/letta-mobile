package com.letta.mobile.brand

import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * `com.letta.mobile.brand`: reads `branding/brand.properties` from the root project and exposes it
 * as the `brand` extension ([BrandIdentity]), so module build files read the product name and icon
 * paths from that one file instead of repeating them.
 *
 * The file is read through [org.gradle.api.provider.ProviderFactory.fileContents], so editing it
 * invalidates the configuration cache.
 */
class BrandPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val file = project.rootProject.layout.projectDirectory.file(BRAND_FILE)
        val text = project.providers.fileContents(file).asText
        val identity = BrandIdentity.parse(
            requireNotNull(text.orNull) { "Missing ${file.asFile}: the brand source file" },
        )
        project.extensions.add(BrandIdentity::class.java, EXTENSION_NAME, identity)
    }

    companion object {
        /** The brand source, relative to the root project directory. */
        const val BRAND_FILE = "branding/brand.properties"

        const val EXTENSION_NAME = "brand"
    }
}
