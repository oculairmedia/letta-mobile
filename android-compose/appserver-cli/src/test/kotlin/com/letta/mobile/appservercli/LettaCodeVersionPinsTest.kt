package com.letta.mobile.appservercli

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * letta-mobile-bzvro.10 (F10): the bundled-runtime pin is stated in four places that must agree:
 * the desktop build (`desktopLettaCodeVersion`), the runtime package and manifest it installs, and
 * the restart-replay evidence the appserver-cli probes certify. See the "letta-code version pins"
 * table in sharedLogic's `data/transport/appserver/README.md`.
 */
class LettaCodeVersionPinsTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile && File(it, "desktop").isDirectory }

    private fun read(path: String): String = File(root, path).readText()

    @Test
    fun theBundledRuntimePinAgreesEverywhere() {
        val build = Regex("""val desktopLettaCodeVersion = "([^"]+)"""").find(read("desktop/build.gradle.kts"))
            ?.groupValues?.get(1)
        val manifest = Regex(""""lettaCode"\s*:\s*"([^"]+)"""").find(read("desktop/runtime/runtime-manifest.json"))
            ?.groupValues?.get(1)
        val pkg = Regex(""""@letta-ai/letta-code"\s*:\s*"([^"]+)"""").find(read("desktop/runtime/package.json"))
            ?.groupValues?.get(1)
        val evidence = AppServerRestartReplayEvidence.PINNED_LETTA_CODE_VERSION

        assertEquals(evidence, build, "desktop/build.gradle.kts desktopLettaCodeVersion")
        assertEquals(evidence, manifest, "desktop/runtime/runtime-manifest.json")
        assertEquals(evidence, pkg, "desktop/runtime/package.json")
    }

    @Test
    fun theDocumentedPinsMatchTheSourcesOfTruth() {
        val pinned = AppServerRestartReplayEvidence.PINNED_LETTA_CODE_VERSION
        val baseline = Regex(""""version"\s*:\s*"([^"]+)"""")
            .find(read("sharedLogic/src/jvmTest/resources/appserver/app-server-v2-contract-matrix.json"))
            ?.groupValues?.get(1)!!
        val table = read("sharedLogic/src/commonMain/kotlin/com/letta/mobile/data/transport/appserver/README.md")
        assertTrue("| Desktop bundled runtime | $pinned |" in table, "README runtime pin")
        assertTrue("| Wire contract baseline | $baseline |" in table, "README contract pin")
    }
}
