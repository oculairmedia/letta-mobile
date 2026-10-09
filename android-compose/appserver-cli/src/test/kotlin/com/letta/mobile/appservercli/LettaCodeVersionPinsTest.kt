package com.letta.mobile.appservercli

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * letta-mobile-bzvro.10 (F10) / letta-mobile-340tc: the letta-code pins are stated in several
 * places that must agree. The desktop bundled runtime (`desktopLettaCodeVersion`, the runtime
 * package, its lock file and manifest) and the wire contract baseline move together (0.33.6). The
 * restart-replay evidence is a separate pin that is allowed to lag: it can only be re-captured
 * against a live server, so it is held at the version it was observed on and the README table
 * says so. The Android embedded runtime is a documented ceiling (0.26.1) and is checked against
 * its own source of truth. See the "letta-code version pins" table in sharedLogic's
 * `data/transport/appserver/README.md`.
 */
class LettaCodeVersionPinsTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "desktop").isDirectory }

    private fun read(path: String): String = File(root, path).readText()

    private fun first(regex: Regex, text: String): String? = regex.find(text)?.groupValues?.get(1)

    private val bundled: String
        get() = first(Regex("""val desktopLettaCodeVersion = "([^"]+)""""), read("desktop/build.gradle.kts"))!!

    private val baseline: String
        get() = first(
            Regex(""""version"\s*:\s*"([^"]+)""""),
            read("sharedLogic/src/jvmTest/resources/appserver/app-server-v2-contract-matrix.json"),
        )!!

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
    fun theDesktopRuntimeAndTheContractBaselineMoveTogether() {
        assertEquals(baseline, bundled, "desktop bundled runtime must equal the wire contract baseline")
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
    fun theDocumentedPinsMatchTheSourcesOfTruth() {
        val embedded = first(
            Regex("""embeddedLettaCodeVersion\s*=\s*"([^"]+)""""),
            read("app/build.gradle.kts"),
        )!!
        assertTrue("| Desktop bundled runtime | $bundled |" in table, "README runtime pin")
        assertTrue("| Wire contract baseline | $baseline |" in table, "README contract pin")
        assertTrue("| Android embedded runtime (ceiling) | $embedded |" in table, "README embedded pin")
    }
}
