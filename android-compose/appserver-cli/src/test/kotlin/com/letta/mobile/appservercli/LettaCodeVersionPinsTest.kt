package com.letta.mobile.appservercli

import com.letta.mobile.data.transport.appserver.LettaCodeContract
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * letta-mobile-by4vm.110 D1: there is ONE letta-code pin. `letta-code-runtime/letta-code-runtime.properties`
 * (`lettaCodeVersion`) is its source of truth: the Android embedded runtime and the desktop bundled
 * runtime are built from it, and the wire contract (the matrix's `baseline.version`), the desktop
 * runtime package files, the `appserver-contract-baseline` CI job, the README pin table,
 * [LettaCodeContract] and the pins quoted in CLAUDE.md / AGENTS.md / the CLI READMEs must all name
 * it; this test fails the moment one of them disagrees. Where the version is written is listed once,
 * in [SITES_FILE], which `scripts/appserver/letta-code-upgrade.mjs apply` also rewrites from: a site
 * missing from that list is a pin neither the test nor the upgrade command knows about. The
 * restart-replay evidence (needs a live re-capture) is the one documented exception, checked
 * against its own evidence file. See the "letta-code version pins" table in sharedLogic's
 * `data/transport/appserver/README.md`.
 */
class LettaCodeVersionPinsTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "desktop").isDirectory }

    private fun read(path: String): String = File(root, path).readText()

    private fun first(regex: Regex, text: String): String? = regex.find(text)?.groupValues?.get(1)

    private val pin: String
        get() = first(Regex("""(?m)^lettaCodeVersion=(\S+)$"""), read(PIN_FILE))!!

    private val contract: String
        get() = first(
            Regex(""""version"\s*:\s*"([^"]+)""""),
            read(CONTRACT_MATRIX),
        )!!

    private val table: String
        get() = read("sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/transport/appserver/README.md")

    /** One entry of [SITES_FILE]: a file (relative to the repository root) and a regex whose group 1 is the version. */
    private data class Site(val path: String, val pattern: Regex, val count: Int)

    private val sites: List<Site>
        get() = Json.parseToJsonElement(File(root, SITES_FILE).readText()).jsonObject.getValue("sites").jsonArray.map {
            val site = it.jsonObject
            Site(site.string("path"), Regex(site.string("pattern")), site.getValue("count").jsonPrimitive.int)
        }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    @Test
    fun everyListedVersionSiteNamesThePin() {
        sites.forEach { site ->
            val found = site.pattern.findAll(File(root.parentFile, site.path).readText()).map { it.groupValues[1] }.toList()
            assertEquals(List(site.count) { pin }, found, "${site.path} /${site.pattern.pattern}/")
        }
    }

    @Test
    fun theSiteListCoversThePinFileTheDesktopRuntimeAndTheContract() {
        val listed = sites.map { it.path }.toSet()
        listOf(
            "android-compose/$PIN_FILE",
            "android-compose/desktop/runtime/runtime-manifest.json",
            "android-compose/desktop/runtime/package.json",
            "android-compose/desktop/runtime/package-lock.json",
            "android-compose/$CONTRACT_MATRIX",
            "CLAUDE.md",
            "AGENTS.md",
            "android-compose/cli/README.md",
            "android-compose/appserver-cli/README.md",
        ).forEach { assertTrue(it in listed, "$SITES_FILE must list $it") }
        assertTrue(
            PIN_FILE in read("desktop/build.gradle.kts"),
            "desktop/build.gradle.kts must read the pin file, not a literal",
        )
    }

    @Test
    fun theWireContractIsThePin() {
        assertEquals(pin, contract, "the contract matrix baseline.version must equal the pin")
    }

    @Test
    fun theCiJobInstallsTheContractFromTheMatrixAndNoLiteral() {
        val workflow = File(root.parentFile, ".github/workflows/appserver-contract.yml").readText()

        assertTrue("matrix=android-compose/$CONTRACT_MATRIX" in workflow, "CI must read the contract matrix")
        assertTrue("jq -r .baseline.version \"\$matrix\"" in workflow, "CI must take the version from baseline.version")
        assertTrue(
            "@letta-ai/letta-code@\${{ steps.pin.outputs.version }}" in workflow,
            "CI must install the version it read from the matrix",
        )
        assertEquals(emptyList<String>(), QUOTED_PIN.findAll(workflow).map { it.value }.toList(), "CI must not hard-code a letta-code version")
    }

    @Test
    fun theReadmeNamesOneContract() {
        assertTrue("Wire types are pinned to `@letta-ai/letta-code@$contract`" in table, "README intro pin")
        assertTrue("There is **one** wire contract: **$contract**" in table, "README one-contract sentence")
        assertTrue(
            "| THE pin: wire contract, desktop bundled runtime, Android embedded runtime | $contract |" in table,
            "README pin row",
        )
    }

    @Test
    fun theKotlinConstantIsThePin() {
        assertEquals(pin, LettaCodeContract.VERSION, "LettaCodeContract.VERSION")
    }

    @Test
    fun everyQuotedPinInTheGuidesIsTheContract() {
        // The Markdown pin sites of SITES_FILE; none of them may quote another letta-code version.
        val guides = sites.map { it.path }.filter { it.endsWith(".md") && !it.startsWith("docs/") }.distinct()
            .associateWith { File(root.parentFile, it).readText() }
        guides.forEach { (name, text) ->
            val quoted = QUOTED_PIN.findAll(text).map { it.groupValues[1] }.toSet()
            assertEquals(setOf(contract), quoted, "$name must quote only the $contract contract")
        }
    }

    @Test
    fun theReplayEvidencePinIsExplicitAndMatchesTheEvidenceFile() {
        val evidencePin = AppServerRestartReplayEvidence.PINNED_LETTA_CODE_VERSION
        val file = first(
            Regex(""""version"\s*:\s*"([^"]+)""""),
            read("appserver-cli/src/test/resources/appserver/restart-replay-evidence.json"),
        )
        assertEquals(evidencePin, file, "restart-replay-evidence.json source.version")
        assertTrue("| Restart-replay evidence | $evidencePin |" in table, "README replay-evidence pin")
    }

    @Test
    fun theReadmeNamesThePinFile() {
        assertTrue("`$PIN_FILE`" in table, "README must name the pin file")
    }

    private companion object {
        const val PIN_FILE = "letta-code-runtime/letta-code-runtime.properties"
        const val SITES_FILE = "letta-code-runtime/letta-code-version-sites.json"
        const val CONTRACT_MATRIX = "sharedLogic/src/jvmTest/resources/appserver/app-server-v2-contract-matrix.json"
        val QUOTED_PIN = Regex("""@letta-ai/letta-code@(\d+\.\d+\.\d+)""")
    }
}
