package com.letta.mobile.embedded

import org.gradle.api.Project
import org.gradle.api.provider.Provider
import java.util.Properties

/** Pins from `app/embedded-runtime/embedded-runtime.properties`. */
internal data class EmbeddedRuntimePins(
    val lettaCodeVersion: String,
    val lettaCodeIntegrity: String,
    val assetRevision: String,
    val libnodeVersion: String,
    val libnodeSha256: String,
    val deviceNpmVersion: String,
) {
    val libnodeArchiveName: String get() = "nodejs-mobile-$libnodeVersion-android.zip"

    val libnodeUrl: String
        get() = "https://github.com/nodejs-mobile/nodejs-mobile/releases/download/$libnodeVersion/$libnodeArchiveName"

    companion object {
        fun load(project: Project, path: String): EmbeddedRuntimePins {
            val file = project.layout.projectDirectory.file(path)
            val text = project.providers.fileContents(file).asText.get()
            val props = Properties().apply { load(text.reader()) }
            fun pin(key: String): String =
                checkNotNull(props.getProperty(key)?.trim()?.takeIf { it.isNotEmpty() }) { "Missing $key in $path" }
            return EmbeddedRuntimePins(
                lettaCodeVersion = pin("lettaCodeVersion"),
                lettaCodeIntegrity = pin("lettaCodeIntegrity"),
                assetRevision = pin("assetRevision"),
                libnodeVersion = pin("libnodeVersion"),
                libnodeSha256 = pin("libnodeSha256"),
                deviceNpmVersion = pin("deviceNpmVersion"),
            )
        }
    }
}

/**
 * Embedded-runtime opt-in. Each flag resolves in this order:
 *   1. -PembedLettaCodeNative=<bool> / -PembedLettaCodeAssets=<bool> (explicit; CI/release pass them)
 *   2. local.properties `embedLettaCodeNative=true` / `embedLettaCodeAssets=true` (machine-local
 *      default — gitignored, so dev machines can build the embedded runtime into debug APKs
 *      without burdening CI, which never sets it)
 *   3. assets follow native; native defaults to false
 */
internal class EmbeddedRuntimeFlags(project: Project) {
    private val localProps = Properties().apply {
        val file = project.rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }

    val nativeEnabled: Provider<Boolean> = project.providers.gradleProperty("embedLettaCodeNative")
        .map { it.isTrue() }
        .orElse(localProps.getProperty("embedLettaCodeNative").isTrue())

    val assetsEnabled: Provider<Boolean> = project.providers.gradleProperty("embedLettaCodeAssets")
        .map { it.isTrue() }
        .orElse(
            project.providers.provider { localProps.getProperty("embedLettaCodeAssets") }
                .map { it.isTrue() }
                .orElse(nativeEnabled),
        )

    private fun String?.isTrue(): Boolean = equals("true", ignoreCase = true)
}
