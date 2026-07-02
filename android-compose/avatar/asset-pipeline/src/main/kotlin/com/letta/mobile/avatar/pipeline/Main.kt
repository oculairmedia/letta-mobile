package com.letta.mobile.avatar.pipeline

import com.letta.mobile.avatar.core.AvatarAssetSource
import com.letta.mobile.avatar.core.AvatarImportVisibility
import com.letta.mobile.avatar.core.AvatarLicense
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

private const val USAGE = """
avatar-import — validate a .vrm/.glb/.gltf and register it in a local catalog.

Usage: avatar-import <source-file> --catalog <dir> [options]

Options:
  --catalog <dir>            Catalog directory (required)
  --id <id>                  Explicit avatar id (default: derived slug+hash)
  --name <name>              Display name (default: source file name)
  --license-name <name>      License name, e.g. CC0
  --license-url <url>        License URL
  --creator <name>           Asset creator
  --source-url <url>         Origin URL (VRoid Hub / Sketchfab page, …)
  --redistribution <bool>    Whether redistribution is permitted (omit = unknown)
  --shared                   Import into the shared catalog (default: local/private)
"""

fun main(args: Array<String>) {
    if (args.isEmpty() || args[0] in setOf("-h", "--help")) {
        println(USAGE.trimIndent())
        exitProcess(if (args.isEmpty()) 1 else 0)
    }

    val sourcePath = Path.of(args[0])
    if (!Files.isRegularFile(sourcePath)) {
        System.err.println("Source file not found: $sourcePath")
        exitProcess(1)
    }

    var catalogDir: Path? = null
    var id: String? = null
    var name: String? = null
    var licenseName: String? = null
    var licenseUrl: String? = null
    var creator: String? = null
    var sourceUrl: String? = null
    var redistribution: Boolean? = null
    var shared = false

    var index = 1
    fun value(flag: String): String {
        if (index + 1 >= args.size) {
            System.err.println("$flag needs a value")
            exitProcess(1)
        }
        index += 1
        return args[index]
    }
    while (index < args.size) {
        when (val flag = args[index]) {
            "--catalog" -> catalogDir = Path.of(value(flag))
            "--id" -> id = value(flag)
            "--name" -> name = value(flag)
            "--license-name" -> licenseName = value(flag)
            "--license-url" -> licenseUrl = value(flag)
            "--creator" -> creator = value(flag)
            "--source-url" -> sourceUrl = value(flag)
            "--redistribution" -> redistribution = value(flag).toBooleanStrictOrNull()
            "--shared" -> shared = true
            else -> {
                System.err.println("Unknown option: $flag")
                exitProcess(1)
            }
        }
        index += 1
    }
    val resolvedCatalogDir = catalogDir ?: run {
        System.err.println("--catalog <dir> is required")
        exitProcess(1)
    }

    val request = AvatarImportRequest(
        sourceFileName = sourcePath.fileName.toString(),
        bytes = Files.readAllBytes(sourcePath),
        id = id,
        displayName = name,
        license = AvatarLicense(
            sourceUrl = sourceUrl,
            creator = creator,
            licenseName = licenseName,
            licenseUrl = licenseUrl,
            allowRedistribution = redistribution,
        ),
        source = AvatarAssetSource(url = sourceUrl ?: sourcePath.toUri().toString(), kind = "cli"),
        visibility = if (shared) {
            AvatarImportVisibility.SHARED_CATALOG
        } else {
            AvatarImportVisibility.PRIVATE_LOCAL
        },
    )

    when (val result = runBlocking { AvatarImporter(resolvedCatalogDir).import(request) }) {
        is AvatarImportResult.Imported -> {
            println("Imported ${result.model.id} (${result.manifest.format})")
            println("  asset:    ${result.assetPath}")
            println("  manifest: ${result.manifestPath}")
            println("  visibility: ${result.visibility}")
            result.warnings.forEach { println("  warning: $it") }
        }
        is AvatarImportResult.Rejected -> {
            System.err.println("Rejected: ${result.reason}")
            exitProcess(2)
        }
    }
}
