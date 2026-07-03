package com.letta.mobile.avatar.pipeline

import com.letta.mobile.avatar.catalog.AvatarCatalog
import com.letta.mobile.avatar.catalog.AvatarCatalogCodec
import com.letta.mobile.avatar.catalog.JsonFileAvatarCatalogStore
import com.letta.mobile.avatar.core.AvatarAssetSource
import com.letta.mobile.avatar.core.AvatarDetectionException
import com.letta.mobile.avatar.core.AvatarFormat
import com.letta.mobile.avatar.core.AvatarFormatDetector
import com.letta.mobile.avatar.core.AvatarImportDecision
import com.letta.mobile.avatar.core.AvatarImportPolicy
import com.letta.mobile.avatar.core.AvatarImportVisibility
import com.letta.mobile.avatar.core.AvatarLicense
import com.letta.mobile.avatar.core.AvatarManifest
import com.letta.mobile.avatar.core.AvatarManifestCodec
import com.letta.mobile.avatar.core.AvatarModel
import com.letta.mobile.avatar.core.toManifest
import com.letta.mobile.avatar.core.toModel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything the importer needs to know about one incoming asset. */
data class AvatarImportRequest(
    /** Original file name — drives id derivation and packaged extension. */
    val sourceFileName: String,
    val bytes: ByteArray,
    val id: String? = null,
    val displayName: String? = null,
    val license: AvatarLicense = AvatarLicense(),
    val source: AvatarAssetSource = AvatarAssetSource(),
    val visibility: AvatarImportVisibility = AvatarImportVisibility.PRIVATE_LOCAL,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = sourceFileName.hashCode()
}

sealed interface AvatarImportResult {
    data class Imported(
        val model: AvatarModel,
        val manifest: AvatarManifest,
        /** May be downgraded from the requested visibility by license policy. */
        val visibility: AvatarImportVisibility,
        val assetPath: Path,
        val manifestPath: Path,
        val preservedSourcePath: Path,
        val warnings: List<String>,
    ) : AvatarImportResult

    data class Rejected(val reason: String) : AvatarImportResult
}

/**
 * The import pipeline: detect → license-gate → inspect → hash → write asset +
 * preserved original + manifest → register in the catalog. Writes land in a
 * self-contained catalog directory:
 *
 * ```
 * <catalogDir>/
 *   catalog.json                       entry registry (AvatarCatalogCodec)
 *   assets/<id>.<ext>                  packaged runtime asset
 *   manifests/<id>.avatar.manifest.json
 *   sources/<id>.source.<ext>          untouched original, for audits
 * ```
 *
 * Rejections (license, structural errors, non-glTF bytes) write nothing.
 */
class AvatarImporter(
    private val catalogDir: Path,
    private val inspector: GltfInspector = BuiltinGltfInspector,
    private val catalog: AvatarCatalog =
        AvatarCatalog(JsonFileAvatarCatalogStore(catalogDir.resolve(AvatarCatalogCodec.FILE_NAME))),
) {
    suspend fun import(sourcePath: Path): AvatarImportResult =
        import(
            AvatarImportRequest(
                sourceFileName = sourcePath.fileName.toString(),
                bytes = withContext(Dispatchers.IO) { Files.readAllBytes(sourcePath) },
                source = AvatarAssetSource(url = sourcePath.toUri().toString(), kind = "local"),
            ),
        )

    suspend fun import(request: AvatarImportRequest): AvatarImportResult {
        val warnings = mutableListOf<String>()

        // 1. Detect format + normalize VRM metadata.
        val detection = try {
            AvatarFormatDetector.detect(request.bytes)
        } catch (e: AvatarDetectionException) {
            return AvatarImportResult.Rejected(e.message ?: "Not a glTF/VRM asset")
        }

        // 2. License gate — before any bytes are written.
        val effectiveVisibility = when (
            val decision = AvatarImportPolicy.evaluate(request.license, request.visibility)
        ) {
            is AvatarImportDecision.Rejected -> return AvatarImportResult.Rejected(decision.reason)
            is AvatarImportDecision.AllowedLocalOnly -> {
                warnings += decision.reason
                AvatarImportVisibility.PRIVATE_LOCAL
            }
            AvatarImportDecision.Allowed -> request.visibility
        }

        // 3. Structural inspection — errors fail the import.
        val inspection = inspector.inspect(request.bytes, detection)
        if (inspection.errors.isNotEmpty()) {
            return AvatarImportResult.Rejected(
                "Asset failed validation: ${inspection.errors.joinToString("; ")}",
            )
        }
        warnings += inspection.warnings

        // 4. Identity + hash. Explicit ids become path segments below, so
        // anything that isn't a plain safe file name (separators, "..", a
        // leading dot) is rejected rather than allowed to escape catalogDir.
        request.id?.let { explicit ->
            if (!SAFE_ID_REGEX.matches(explicit)) {
                return AvatarImportResult.Rejected(
                    "Invalid id '$explicit': ids must match ${SAFE_ID_REGEX.pattern}",
                )
            }
        }
        val sha256 = sha256Hex(request.bytes)
        val baseName = request.sourceFileName.substringBeforeLast('.')
            .ifBlank { "avatar" }
        val id = request.id ?: "${slugify(baseName)}-${sha256.take(8)}"
        val displayName = request.displayName ?: baseName

        // 5. Write the catalog directory entries.
        val extension = packagedExtension(request.sourceFileName, detection.format)
        val assetPath = catalogDir.resolve("assets").resolve("$id.$extension")
        val manifestPath = catalogDir.resolve("manifests")
            .resolve("$id.${AvatarManifest.FILE_NAME}")
        val preservedSourcePath = catalogDir.resolve("sources")
            .resolve("$id.source.$extension")

        val manifest = detection.toManifest(
            id = id,
            displayName = displayName,
            license = request.license,
            source = request.source,
            sha256 = sha256,
            stats = inspection.stats,
            extraWarnings = warnings,
        )
        // Catalog entries carry CATALOG-RELATIVE uris ("assets/<id>.glb") so a
        // catalog directory stays self-contained when moved, synced, or used
        // as a shared catalog on another machine. Consumers resolve them with
        // [resolveCatalogUri] against whatever directory they loaded from.
        val model = manifest.toModel(
            uri = catalogRelative(assetPath),
            manifestUri = catalogRelative(manifestPath),
        )

        withContext(Dispatchers.IO) {
            // Atomic writes: on re-import of an existing id, the previous
            // asset/manifest stay intact until each replacement is complete —
            // a crash mid-import never leaves a catalog entry pointing at a
            // truncated file.
            writeAtomically(assetPath, request.bytes)
            writeAtomically(preservedSourcePath, request.bytes)
            writeAtomically(manifestPath, AvatarManifestCodec.encode(manifest).encodeToByteArray())
        }

        // 6. Register. AvatarCatalog.upsert merges against the persisted
        // catalog.json inside its lock, so a fresh importer never clobbers
        // existing entries.
        catalog.upsert(model)

        return AvatarImportResult.Imported(
            model = model,
            manifest = manifest,
            visibility = effectiveVisibility,
            assetPath = assetPath,
            manifestPath = manifestPath,
            preservedSourcePath = preservedSourcePath,
            warnings = manifest.warnings,
        )
    }

    private fun packagedExtension(sourceFileName: String, format: AvatarFormat): String {
        val original = sourceFileName.substringAfterLast('.', "").lowercase()
        if (original in setOf("vrm", "glb", "gltf")) return original
        return when (format) {
            AvatarFormat.VRM_1, AvatarFormat.VRM_0 -> "vrm"
            AvatarFormat.GLB -> "glb"
            AvatarFormat.GLTF -> "gltf"
        }
    }

    private fun slugify(value: String): String =
        value.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .split('-')
            .filter { it.isNotEmpty() }
            .joinToString("-")
            .ifBlank { "avatar" }

    private fun writeAtomically(target: Path, bytes: ByteArray) {
        Files.createDirectories(target.parent)
        val temp = target.resolveSibling("${target.fileName}.tmp")
        Files.write(temp, bytes)
        try {
            Files.move(
                temp,
                target,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Forward-slash catalog-relative path, portable across OS + machines. */
    private fun catalogRelative(target: Path): String =
        catalogDir.toAbsolutePath().relativize(target.toAbsolutePath())
            .joinToString("/") { it.toString() }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> (byte.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private companion object {
        /** Plain safe file-name segment: no separators, no leading dot. */
        val SAFE_ID_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
    }
}

/**
 * Resolve a catalog-relative [AvatarModel.uri]/[AvatarModel.manifestUri]
 * against the directory the catalog was loaded from. Rejects uris that would
 * escape [catalogDir].
 */
fun resolveCatalogUri(catalogDir: Path, uri: String): Path {
    val base = catalogDir.toAbsolutePath().normalize()
    val resolved = base.resolve(uri).normalize()
    require(resolved.startsWith(base)) { "Catalog uri escapes the catalog directory: $uri" }
    return resolved
}
