package com.letta.mobile.runtime.local

import android.content.Context
import com.letta.mobile.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PreparedLettaCodeProject(
    val projectDir: File,
    val entrypoint: File,
    val workingDirectory: File,
    val storageDirectory: File,
    val homeDirectory: File,
)

@Singleton
class EmbeddedLettaCodeAssetExtractor @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    suspend fun prepare(): PreparedLettaCodeProject = withContext(Dispatchers.IO) {
        if (!BuildConfig.EMBEDDED_LETTACODE_ASSETS_ENABLED) {
            throw IllegalStateException(
                "Embedded LettaCode assets are not enabled in this build. " +
                    "Build with -PembedLettaCodeAssets=true to package @letta-ai/letta-code.",
            )
        }

        val baseDir = File(context.filesDir, "embedded-lettacode")
        val projectDir = File(baseDir, "nodejs-project")
        val marker = File(baseDir, "asset-version.txt")
        val expectedMarker = BuildConfig.EMBEDDED_LETTACODE_VERSION
        val entrypoint = File(projectDir, "node_modules/@letta-ai/letta-code/letta.js")

        if (marker.readTextOrNull() != expectedMarker || !entrypoint.isFile) {
            projectDir.deleteRecursively()
            projectDir.mkdirs()
            copyAssetTree(ASSET_ROOT, projectDir)
            marker.parentFile?.mkdirs()
            marker.writeText(expectedMarker)
        }

        if (!entrypoint.isFile) {
            throw IllegalStateException("Embedded LettaCode entrypoint was not found at ${entrypoint.absolutePath}.")
        }

        val workingDirectory = File(baseDir, "workdir").apply { mkdirs() }
        val storageDirectory = File(baseDir, "local-backend").apply { mkdirs() }
        val homeDirectory = File(baseDir, "home").apply { mkdirs() }
        PreparedLettaCodeProject(
            projectDir = projectDir,
            entrypoint = entrypoint,
            workingDirectory = workingDirectory,
            storageDirectory = storageDirectory,
            homeDirectory = homeDirectory,
        )
    }

    private fun copyAssetTree(assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }

        target.mkdirs()
        children.forEach { child ->
            copyAssetTree("$assetPath/$child", File(target, child))
        }
    }

    private companion object {
        private const val ASSET_ROOT = "letta-code/nodejs-project"
    }
}

private fun File.readTextOrNull(): String? =
    runCatching { if (isFile) readText() else null }.getOrNull()
