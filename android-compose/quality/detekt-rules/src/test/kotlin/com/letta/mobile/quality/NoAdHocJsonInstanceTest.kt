package com.letta.mobile.quality

import io.gitlab.arturbosch.detekt.test.compileAndLint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class NoAdHocJsonInstanceTest {
    private val adHoc = """
        import kotlinx.serialization.json.Json
        val wire = Json { ignoreUnknownKeys = true }
        val derived = Json(wire) { prettyPrint = true }
        val qualified = kotlinx.serialization.json.Json { isLenient = true }
    """.trimIndent()

    @Test
    fun `new Json configurations are reported`() {
        assertEquals(3, NoAdHocJsonInstance(isBaselined = { false }).compileAndLint(adHoc).size)
    }

    @Test
    fun `references to a canonical instance and unrelated Json calls pass`() {
        val code = """
            import kotlinx.serialization.json.Json
            object LettaJson { val wire: Json = Json.Default }
            sealed interface Body { data class Json(val raw: String) : Body }
            val json: Json = LettaJson.wire
            val body = Body.Json("{}")
        """.trimIndent()
        assertEquals(0, NoAdHocJsonInstance(isBaselined = { false }).compileAndLint(code).size)
    }

    @Test
    fun `baselined files are skipped`() {
        assertEquals(0, NoAdHocJsonInstance(isBaselined = { true }).compileAndLint(adHoc).size)
    }

    @Test
    fun `baseline lookup resolves paths against the android compose directory`(@TempDir root: File) {
        val baseline = File(root, "android-compose/architecture-tests/json-instances-baseline.txt")
        baseline.parentFile.mkdirs()
        baseline.writeText("# comment\nandroid-compose/app/src/main/java/Old.kt\n")
        val compose = "${root.invariantSeparatorsPath}/android-compose"

        assertTrue(JsonInstanceBaseline.contains("$compose/app/src/main/java/Old.kt"))
        assertFalse(JsonInstanceBaseline.contains("$compose/app/src/main/java/New.kt"))
        assertFalse(JsonInstanceBaseline.contains("/elsewhere/Old.kt"))
    }
}
