package com.letta.mobile.appservercli

import com.letta.mobile.data.transport.appserver.LettaCodeContract
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * letta-mobile-ckmub.5: there is ONE letta-code wire contract (0.34.8). The contract matrix's
 * `baseline.version` is its source of truth; the desktop bundled runtime, the
 * `appserver-contract-baseline` CI job, the README pin table, [LettaCodeContract] and the pins
 * quoted in CLAUDE.md / AGENTS.md / the CLI READMEs must all name it, and this test fails the
 * moment one of them disagrees. The restart-replay evidence (needs a live re-capture) and the
 * dev-only Android embedded runtime (0.26.1, Node 18) are the documented exceptions, each checked
 * against its own source of truth (letta-mobile-bzvro.10 / letta-mobile-340tc before it). See the
 * "letta-code version pins" table in sharedLogic's `data/transport/appserver/README.md`.
 */
class LettaCodeVersionPinsTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "desktop").isDirectory }

    private fun read(path: String): String = File(root, path).readText()

    private fun first(regex: Regex, text: String): String? = regex.find(text)?.groupValues?.get(1)

    private val bundled: String
        get() = first(Regex("""val desktopLettaCodeVersion = "([^"]+)""""), read("desktop/build.gradle.kts"))!!

    private val contract: String
        get() = first(
            Regex(""""version"\s*:\s*"([^"]+)""""),
            read(CONTRACT_MATRIX),
        )!!

    private val embedded: String
        get() = first(Regex("""embeddedLettaCodeVersion\s*=\s*"([^"]+)""""), read("app/build.gradle.kts"))!!

    private val table: String
        get() = read("sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/transport/appserver/README.md")

    @Test
    fun theBundledRuntimePinAgreesEverywhere() {
        val manifest = first(Regex(""""lettaCode"\s*:\s*"([^"]+)""""), read("desktop/runtime/runtime-manifest.json"))
        val pkg = first(Regex(""""@letta-ai/letta-code"\s*:\s*"([^"]+)""""), read("desktop/runtime/package.json"))
        val lock = first(
            Regex(""""node_modules/@letta-ai/letta-code": \{\s*"version": "([^"]+)""""),
            read("desktop/runtime/package-lock.json"),
        )

        assertEquals(bundled, manifest, "desktop/runtime/runtime-manifest.json")
        assertEquals(bundled, pkg, "desktop/runtime/package.json")
        assertEquals(bundled, lock, "desktop/runtime/package-lock.json")
    }

    @Test
    fun theDesktopRuntimeIsTheContract() {
        assertEquals(contract, bundled, "desktop bundled runtime must equal the wire contract")
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
        assertTrue("| Wire contract | $contract |" in table, "README contract row")
    }

    @Test
    fun theKotlinConstantsCiteTheSourcesOfTruth() {
        assertEquals(contract, LettaCodeContract.VERSION, "LettaCodeContract.VERSION")
        assertEquals(embedded, LettaCodeContract.ANDROID_EMBEDDED_VERSION, "LettaCodeContract.ANDROID_EMBEDDED_VERSION")
    }

    @Test
    fun everyQuotedPinInTheGuidesIsTheContract() {
        val guides = mapOf(
            "CLAUDE.md" to File(root.parentFile, "CLAUDE.md").readText(),
            "AGENTS.md" to File(root.parentFile, "AGENTS.md").readText(),
            "cli/README.md" to read("cli/README.md"),
            "appserver-cli/README.md" to read("appserver-cli/README.md"),
        )
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
    fun theEmbeddedRuntimeIsDocumentedAsDevOnly() {
        assertTrue("| Android embedded runtime (dev-only) | $embedded |" in table, "README embedded pin")
        assertTrue("**Development only, not a production path.**" in table, "README must say the embedded runtime is dev-only")
    }

    private companion object {
        const val CONTRACT_MATRIX = "sharedLogic/src/jvmTest/resources/appserver/app-server-v2-contract-matrix.json"
        val QUOTED_PIN = Regex("""@letta-ai/letta-code@(\d+\.\d+\.\d+)""")
    }
}
