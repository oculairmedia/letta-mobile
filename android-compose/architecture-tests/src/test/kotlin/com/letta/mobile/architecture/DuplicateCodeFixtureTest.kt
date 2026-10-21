package com.letta.mobile.architecture

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Self-test: the ratchet fails on a crafted cross-module clone and ignores one inside a module. */
class DuplicateCodeFixtureTest {
    @Test
    fun `a block copied into another module is reported and one inside a module is not`(@TempDir root: Path) {
        val desktop = module(root, "desktop")
        val web = module(root, "web")
        desktop.resolve("A.kt").writeText(cloneSource("Desktop"))
        desktop.resolve("B.kt").writeText(cloneSource("DesktopTwin"))
        web.resolve("C.kt").writeText(cloneSource("Web"))

        val sources = DuplicateCodeScan.productionSources(root)
        val pairs = DuplicateCodeScan.crossModuleDuplicates(root, sources, minimumTokens = 50)

        val desktopA = "android-compose/desktop/src/main/kotlin/A.kt"
        val desktopB = "android-compose/desktop/src/main/kotlin/B.kt"
        val webC = "android-compose/web/src/main/kotlin/C.kt"
        check(pairs.keys == setOf("$desktopA|$webC", "$desktopB|$webC")) { "unexpected clone pairs: $pairs" }
        val drift = CountRatchetBaseline.drift(pairs, emptyList(), "fixture-baseline.txt", CountRatchetBaseline.Shrink.MUST_UPDATE)
        check(drift.size == 2 && drift.all { it.startsWith("NEW:") }) { "a cross-module clone must fail the ratchet: $drift" }
        check(DuplicateCodeRatchetTest.failureMessage(drift).contains("Keep ONE implementation in sharedLogic")) {
            "the failure must say what to do"
        }
        val healed = CountRatchetBaseline.drift(emptyMap(), pairs.toList(), "fixture-baseline.txt", CountRatchetBaseline.Shrink.MUST_UPDATE)
        check(healed.size == 2 && healed.all { it.startsWith("STALE:") }) { "a removed clone must lower the baseline: $healed" }
    }

    private fun module(root: Path, name: String): Path {
        val dir = root.resolve("android-compose/$name")
        dir.createDirectories()
        dir.resolve("build.gradle.kts").writeText("")
        return dir.resolve("src/main/kotlin").createDirectories()
    }

    private fun cloneSource(prefix: String) = buildString {
        appendLine("package example")
        appendLine("class ${prefix}Holder {")
        appendLine("    fun compute(values: List<Int>): Int {")
        repeat(12) { i ->
            appendLine("        val step$i = values.filter { it > $i }.map { it * $i + 1 }.sum()")
        }
        appendLine("        return step0 + step1 + step2")
        appendLine("    }")
        appendLine("}")
    }
}
