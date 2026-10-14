package com.letta.mobile.architecture

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readLines

/**
 * Ratchet on ad-hoc kotlinx `Json { }` configurations (letta-mobile-by4vm.20). A production file
 * that is not in `json-instances-baseline.txt` must use a `LettaJson` instance instead of building
 * its own. Stale lines (a file that no longer constructs a Json) do not fail: anyone may delete
 * them, so a migration PR never has to touch the baseline and cannot conflict on it. See
 * docs/architecture/canonical-json.md.
 */
class JsonInstanceRatchetTest {
    private val projectRoot: Path = Path.of(requireNotNull(System.getProperty("architecture.projectRoot")))
    private val baselineFile = projectRoot.resolve("android-compose/architecture-tests/$BASELINE")

    @Test
    fun `no production file outside the baseline constructs its own Json`() {
        val baseline = baselineFile.readLines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
        check(baseline == baseline.sorted() && baseline.size == baseline.toSet().size) {
            "$BASELINE must be sorted with one unique path per line"
        }
        val actual = JsonInstanceScan.scan(projectRoot)
        check(actual.size > 1) { "Scanned ${actual.size} files; the scan is not seeing the source tree" }

        val stale = baseline - actual.toSet()
        if (stale.isNotEmpty()) println("$BASELINE has stale lines you may delete:\n" + stale.joinToString("\n"))

        val added = actual - baseline.toSet()
        check(added.isEmpty()) {
            "New ad-hoc Json { } configuration; use LettaJson.wire, LettaJson.adminRpc or LettaJson.persisted " +
                "(sharedLogic data/serialization/LettaJson.kt) instead of adding to $BASELINE:\n" +
                added.joinToString("\n")
        }
    }

    @Test
    fun `scan matches constructions and ignores references`() {
        assertTrue(JsonInstanceScan.constructsJson("private val json = Json { ignoreUnknownKeys = true }"))
        assertTrue(JsonInstanceScan.constructsJson("val j = Json(from = base) {\n  prettyPrint = true\n}"))
        assertTrue(JsonInstanceScan.constructsJson("val j = kotlinx.serialization.json.Json { }"))
        assertFalse(JsonInstanceScan.constructsJson("val json: Json = LettaJson.wire"))
        assertFalse(JsonInstanceScan.constructsJson("send(OAuthRequestBody.Json(params))"))
        assertFalse(JsonInstanceScan.constructsJson("data class Json(val body: JsonObject) : Body"))
        assertFalse(JsonInstanceScan.constructsJson("class Json(val body: String) {"))
        assertFalse(JsonInstanceScan.constructsJson(" * Never write `Json { }` here."))
        assertFalse(JsonInstanceScan.constructsJson("// Json { isLenient = true }"))
    }

    @Test
    fun `only non test source sets are production`() {
        assertTrue(JsonInstanceScan.isProductionSource("android-compose/app/src/main/java/A.kt"))
        assertTrue(JsonInstanceScan.isProductionSource("android-compose/sharedLogic/src/jvmAndAndroid/kotlin/A.kt"))
        assertFalse(JsonInstanceScan.isProductionSource("android-compose/sharedLogic/src/commonTest/kotlin/A.kt"))
        assertFalse(JsonInstanceScan.isProductionSource("android-compose/app/src/testFixtures/kotlin/A.kt"))
        assertFalse(JsonInstanceScan.isProductionSource("android-compose/app/build/generated/src/main/A.kt"))
        assertFalse(JsonInstanceScan.isProductionSource("android-compose/build-logic/Root.kt"))
    }

    private companion object {
        const val BASELINE = "json-instances-baseline.txt"
    }
}
