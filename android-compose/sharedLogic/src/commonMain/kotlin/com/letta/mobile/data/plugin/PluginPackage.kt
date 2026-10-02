package com.letta.mobile.data.plugin

/** Limits on a `.lcp` package (plan section 3.1 and R3: the relay's asset cap). */
object PluginPackageLimits {
    const val MAX_PACKAGE_BYTES: Long = 50L * 1024 * 1024

    /** What a package may unpack to, so a small zip cannot expand without bound. */
    const val MAX_UNPACKED_BYTES: Long = 4 * MAX_PACKAGE_BYTES

    const val MAX_ENTRIES: Int = 4096
}

/** What a package carries for its runtime (plan section 1). */
sealed interface PluginPayload {
    data class Jar(val path: String) : PluginPayload

    /** Every file outside the manifest and `pages/`: what the process runs from its working directory. */
    data class ProcessFiles(val paths: List<String>) : PluginPayload

    /** A service package carries only its manifest and pages. */
    data object None : PluginPayload
}

/** Where everything is in a package: the manifest at the root, the runtime's [payload], and each page's html by page id. */
data class PluginPackageLayout(val payload: PluginPayload, val pages: Map<String, String>) {
    val manifest: String get() = PluginPackagePaths.MANIFEST
}

enum class PluginPackageProblemCode {
    TOO_LARGE,
    TOO_MANY_ENTRIES,
    UNSAFE_PATH,
    DUPLICATE_ENTRY,
    NOT_A_PACKAGE,
    NO_MANIFEST,
    BAD_MANIFEST,
    MISSING_FILE,
    UNEXPECTED_FILE,
    HASH_MISMATCH,
}

/** One reason a package is refused; [entry] is the file it concerns, when it concerns one. */
data class PluginPackageProblem(val code: PluginPackageProblemCode, val message: String, val entry: String? = null)

/** A package that was read whole and holds together: its content [ref], manifest, layout and files. */
class PluginPackage(
    val ref: PluginPackageRef,
    val manifest: PluginManifest,
    val layout: PluginPackageLayout,
    private val files: Map<String, ByteArray>,
) {
    val paths: Set<String> get() = files.keys

    /** The bytes of [path], or null when the package has no such file. */
    fun file(path: String): ByteArray? = files[path]

    override fun toString(): String = "PluginPackage(${manifest.id} ${manifest.version}, $ref, ${files.size} files)"
}

sealed interface PluginPackageResult {
    data class Read(val pkg: PluginPackage) : PluginPackageResult

    data class Refused(val problems: List<PluginPackageProblem>) : PluginPackageResult
}

/**
 * Reads a `.lcp` package (a zip). The platform reader unpacks and hashes; [PluginPackages.assemble]
 * holds the content together, so the rules are common and the jvm reader is only I/O.
 */
fun interface PluginPackageReader {
    /** [bytes] as a package; when [expectedSha256] is given, a package whose hash differs is refused (pinning). */
    fun read(bytes: ByteArray, expectedSha256: String?): PluginPackageResult
}

/**
 * The layout rules of a package (plan section 3.1): `letta-plugin.json` at the root and valid, the
 * runtime's files present (the jar for `jvm`; the command and working directory for `process`;
 * nothing beyond pages for `service`), every page's html present, every path inside the package.
 */
object PluginPackages {
    /** The package of [entries] (path to bytes, as unpacked) whose content is [ref]. */
    fun assemble(ref: PluginPackageRef, entries: Map<String, ByteArray>): PluginPackageResult {
        val unsafe = entries.keys.filterNot(PluginPackagePaths::isInside)
        if (unsafe.isNotEmpty()) {
            return PluginPackageResult.Refused(unsafe.map { PluginPackageProblem(PluginPackageProblemCode.UNSAFE_PATH, "'$it' points outside the package", it) })
        }
        val manifestBytes = entries[PluginPackagePaths.MANIFEST] ?: return PluginPackageResult.Refused(
            listOf(PluginPackageProblem(PluginPackageProblemCode.NO_MANIFEST, "a package has ${PluginPackagePaths.MANIFEST} at its root")),
        )
        val manifest = when (val parsed = PluginManifestParser.parse(manifestBytes.decodeToString())) {
            is PluginManifestResult.Parsed -> parsed.manifest
            is PluginManifestResult.Refused -> return PluginPackageResult.Refused(
                parsed.problems.map { PluginPackageProblem(PluginPackageProblemCode.BAD_MANIFEST, "${it.path.ifEmpty { "/" }}: ${it.message}", PluginPackagePaths.MANIFEST) },
            )
        }
        val layout = layoutOf(manifest, entries.keys)
        val problems = problems(manifest, layout, entries.keys)
        if (problems.isNotEmpty()) return PluginPackageResult.Refused(problems)
        return PluginPackageResult.Read(PluginPackage(ref, manifest, layout, entries))
    }

    /** Where [manifest] says things are, given the package holds [paths]. */
    fun layoutOf(manifest: PluginManifest, paths: Set<String>): PluginPackageLayout {
        val payload = when (val runtime = manifest.runtime) {
            is PluginRuntime.Jvm -> PluginPayload.Jar(runtime.jar)
            is PluginRuntime.Process -> PluginPayload.ProcessFiles(paths.filterNot(::isManifestOrPage).sorted())
            is PluginRuntime.Service -> PluginPayload.None
        }
        return PluginPackageLayout(payload, manifest.pages.mapValues { (_, page) -> page.html })
    }

    private fun problems(manifest: PluginManifest, layout: PluginPackageLayout, paths: Set<String>): List<PluginPackageProblem> {
        val pages = layout.pages.values.filter { it !in paths }.map(::missing)
        return pages + when (val runtime = manifest.runtime) {
            is PluginRuntime.Jvm -> listOfNotNull(missing(runtime.jar).takeIf { runtime.jar !in paths })
            is PluginRuntime.Process -> processProblems(runtime, paths)
            is PluginRuntime.Service -> paths.filterNot(::isManifestOrPage).map {
                PluginPackageProblem(PluginPackageProblemCode.UNEXPECTED_FILE, "a service package carries only its manifest and pages", it)
            }
        }
    }

    private fun processProblems(runtime: PluginRuntime.Process, paths: Set<String>): List<PluginPackageProblem> {
        val command = runtime.command.removePrefix("./")
        val commandInPackage = command.contains('/')
        val cwd = runtime.cwd.trimEnd('/')
        return listOfNotNull(
            missing(command).takeIf { commandInPackage && command !in paths },
            missing("$cwd/").takeIf { cwd != "." && paths.none { it.startsWith("$cwd/") } },
        )
    }

    private fun isManifestOrPage(path: String): Boolean = path == PluginPackagePaths.MANIFEST || path.startsWith(PluginPackagePaths.PAGES_DIR)

    private fun missing(path: String) = PluginPackageProblem(PluginPackageProblemCode.MISSING_FILE, "the package has no $path", path)
}
