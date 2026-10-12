package com.letta.mobile.data.backend

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.pathString
import kotlin.io.path.readLines
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * letta-mobile-ckmub.4 gate: no product code path builds a REST client for a Letta server.
 *
 * The app has one backend, the letta-code App Server (on-device, direct WebSocket, or over Iroh).
 * The Letta REST backend (`LettaApiClient` and its `*Api` classes, `LettaHttpChatGateway`,
 * `LettaHttpAdminRepositories`, the desktop REST block / cron / skill / slash-command clients) was
 * removed. This scans the product source sets and fails when any of it comes back:
 *  - a request path built on a server address plus `/v1/` (the Letta REST surface);
 *  - a reference to one of the removed REST types;
 *  - the Letta Cloud API host.
 *
 * Dev tooling is out of scope: `cli/`, `appserver-cli/` and `iroh-wrapper-cli/` (the wrapper's own
 * HTTP client talks to the local letta-code App Server, which is the App Server transport).
 */
class NoLettaRestBackendGateTest {
    private data class Finding(val rule: String, val path: String, val line: Int, val text: String) {
        override fun toString() = "$rule $path:$line  $text"
    }

    private val repoRoot: Path = locateRepoRoot()

    @Test
    fun productSourcesBuildNoLettaRestClient() {
        val findings = scan().filterNot { it.isAllowlisted() }
        assertEquals(
            emptyList(),
            findings,
            buildString {
                appendLine("A Letta REST client is back in product code (letta-mobile-ckmub.4 removed that backend).")
                appendLine("Route the call through the App Server (AppServerClient / admin_rpc) instead:")
                findings.forEach { appendLine(" - $it") }
            },
        )
    }

    @Test
    fun gateScansTheProductSourceSets() {
        val roots = productRoots()
        assertTrue(roots.size >= MIN_PRODUCT_ROOTS, "expected the product source sets, found ${roots.map(::rel)}")
        assertTrue(roots.any { rel(it).startsWith("android-compose/core/android-data/") }, "core/android-data not scanned")
        assertTrue(roots.any { rel(it).startsWith("android-compose/desktop/") }, "desktop not scanned")
    }

    private fun scan(): List<Finding> = productRoots().flatMap { root ->
        Files.walk(root).use { paths ->
            paths.asSequence()
                .filter { it.isRegularFile() && it.extension == "kt" }
                .flatMap { file -> scanFile(file).asSequence() }
                .toList()
        }
    }

    private fun scanFile(file: Path): List<Finding> {
        val path = rel(file)
        return file.readLines().withIndex().flatMap { (index, raw) ->
            val code = raw.replace(LINE_COMMENT, "").trim()
            if (code.startsWith("*") || code.startsWith("/*")) return@flatMap emptyList()
            RULES.filter { (_, pattern) -> pattern.containsMatchIn(code) }
                .map { (rule, _) -> Finding(rule, path, index + 1, code.take(MAX_SNIPPET)) }
        }
    }

    private fun Finding.isAllowlisted(): Boolean = ALLOWLIST.any { (rule, pathSuffix) -> this.rule == rule && path.endsWith(pathSuffix) }

    private fun productRoots(): List<Path> {
        val compose = repoRoot.resolve("android-compose")
        val modules = Files.list(compose).use { it.asSequence().filter(Path::isDirectory).toList() } +
            Files.list(compose.resolve("core")).use { it.asSequence().filter(Path::isDirectory).toList() }
        return modules
            .filterNot { it.fileName.toString() in TOOLING_MODULES }
            .flatMap { module -> PRODUCT_SOURCE_SETS.map { module.resolve("src").resolve(it) } }
            .filter { it.isDirectory() }
    }

    private fun rel(path: Path): String = repoRoot.relativize(path).pathString.replace('\\', '/')

    private fun locateRepoRoot(): Path {
        var cur: Path? = Path.of("").toAbsolutePath()
        while (cur != null) {
            if (Files.isRegularFile(cur.resolve("android-compose/settings.gradle.kts"))) return cur
            if (Files.isRegularFile(cur.resolve("settings.gradle.kts")) && cur.fileName?.toString() == "android-compose") {
                return cur.parent
            }
            cur = cur.parent
        }
        error("Could not locate the repository root from ${Path.of("").toAbsolutePath()}")
    }

    private companion object {
        const val MIN_PRODUCT_ROOTS = 6
        const val MAX_SNIPPET = 160

        /** A `//` comment (not the `//` of a URL scheme). */
        val LINE_COMMENT = Regex("""(^|\s)//.*$""")

        val TOOLING_MODULES = setOf(
            "cli", "appserver-cli", "iroh-wrapper-cli", "testutil", "plugin-api-testkit", "architecture-tests",
            "baselineprofile", "macrobenchmark", "perf", "sharedUI-devfixtures", "build", "build-logic", "gradle",
        )

        val PRODUCT_SOURCE_SETS = listOf(
            "main", "commonMain", "jvmMain", "jvmAndAndroid", "androidMain", "wasmJsMain", "jsMain",
        )

        val RULES: List<Pair<String, Regex>> = listOf(
            "rest-path" to Regex("""\$\{?[A-Za-z_.]*(?:[bB]ase[Uu]rl|serverUrl|[uU]rl)[A-Za-z_.()'/]*\}?/v1/"""),
            "rest-type" to Regex(
                """\b(?:LettaApiClient|LettaHttpChatGateway|LettaHttpAdminRepositories|LettaHttpAgentBlockWriter|""" +
                    """DesktopLettaHttpChatGateway|DesktopLettaHttpAdminRepositories|DesktopHttpBlockApi)\b""",
            ),
            "cloud-host" to Regex("""api\.letta\.com"""),
        )

        /**
         * Known remainders owned by other slices; each is a deletion, never a new client.
         * - Cloud validator and Cloud defaults: removed with the Cloud mode by letta-mobile-ckmub.9 (PR #1895).
         * - Server health dot: a `/v1/health/` reachability probe, to become an App Server probe
         *   (follow-up filed by ckmub.4).
         */
        val ALLOWLIST: List<Pair<String, String>> = listOf(
            "rest-path" to "core/android-data/src/main/java/com/letta/mobile/data/api/CloudConnectionValidator.kt",
            "rest-path" to "core/android-data/src/main/java/com/letta/mobile/data/health/ServerHealthRepository.kt",
            "cloud-host" to "app/src/main/java/com/letta/mobile/ui/screens/config/ConfigViewModel.kt",
            "cloud-host" to "sharedUI/src/commonMain/kotlin/com/letta/mobile/ui/settings/BackendSettingsCard.kt",
            "cloud-host" to "sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/session/RemoteLettaBackendDescriptors.kt",
        )
    }
}
