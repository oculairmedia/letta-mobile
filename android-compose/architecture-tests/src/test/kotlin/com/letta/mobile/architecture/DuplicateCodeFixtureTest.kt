package com.letta.mobile.architecture

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Fixture checks for the cross-module and same-module scans and their ratchets. */
class DuplicateCodeFixtureTest {
    @Test
    fun `a block copied into another module is reported and one inside a module is not`(@TempDir root: Path) {
        val desktop = module(root, "desktop")
        val web = module(root, "web")
        desktop.resolve("A.kt").writeText(cloneSource("Desktop"))
        desktop.resolve("B.kt").writeText(cloneSource("DesktopTwin"))
        web.resolve("C.kt").writeText(cloneSource("Web"))

        val sources = DuplicateCodeScan.productionSources(root)
        val pairs = DuplicateCodeScan.crossModuleDuplicates(root, sources, minimumTokens = FIXTURE_MINIMUM_TOKENS)

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

    @Test
    fun `same module clones are ratcheted including clones in one file`(@TempDir root: Path) {
        val first = module(root, "first")
        val second = module(root, "second")
        first.resolve("A.kt").writeText(cloneSource("First") + cloneSource("Again").substringAfter("package example\n"))
        first.resolve("B.kt").writeText(cloneSource("Twin"))
        second.resolve("C.kt").writeText(cloneSource("Other"))
        val sources = DuplicateCodeScan.productionSources(root)
        val pairs = DuplicateCodeScan.sameModuleDuplicates(root, sources, minimumTokens = FIXTURE_MINIMUM_TOKENS)
        val a = "android-compose/first/src/main/kotlin/A.kt"
        val b = "android-compose/first/src/main/kotlin/B.kt"
        check(pairs.keys == setOf("$a|$a", "$a|$b")) { "unexpected same-module pairs: $pairs" }
        val baseline = pairs.toList()
        check(CountRatchetBaseline.drift(pairs, emptyList(), "fixture", CountRatchetBaseline.Shrink.MUST_UPDATE).all { it.startsWith("NEW:") })
        check(CountRatchetBaseline.drift(pairs, baseline, "fixture", CountRatchetBaseline.Shrink.MUST_UPDATE).isEmpty())
        val stale = CountRatchetBaseline.drift(emptyMap(), baseline, "fixture", CountRatchetBaseline.Shrink.MUST_UPDATE)
        check(stale.size == baseline.size && stale.all { it.startsWith("STALE:") }) { "removed clones must fail: $stale" }
    }

    @Test
    fun `same module matches below the token threshold are ignored`(@TempDir root: Path) {
        val sourcesDir = module(root, "small")
        val source = "package example\nfun number() = 42\n"
        sourcesDir.resolve("A.kt").writeText(source)
        sourcesDir.resolve("B.kt").writeText(source)
        val sources = DuplicateCodeScan.productionSources(root)
        val detectable = DuplicateCodeScan.sameModuleDuplicates(root, sources, minimumTokens = TINY_FIXTURE_MINIMUM_TOKENS)
        check(detectable.isNotEmpty()) { "the tiny fixture must contain a detectable match" }
        val pairs = DuplicateCodeScan.sameModuleDuplicates(root, sources)
        check(pairs.isEmpty()) { "a tiny match should be below the threshold: $pairs" }
    }

    private fun module(root: Path, name: String): Path {
        val dir = root.resolve("android-compose/$name")
        dir.createDirectories()
        dir.resolve("build.gradle.kts").writeText("")
        return dir.resolve("src/main/kotlin").createDirectories()
    }

    private companion object {
        const val FIXTURE_MINIMUM_TOKENS = 50
        const val TINY_FIXTURE_MINIMUM_TOKENS = 5
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
