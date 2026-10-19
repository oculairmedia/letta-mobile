package com.letta.mobile.architecture

import com.lemonappdev.konsist.api.Konsist
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.readLines
import kotlin.io.path.writeText

class StructureExportFixtureTest {
    @Test
    fun `parsed named types include nested but exclude comments and local classes`(@TempDir root: Path) {
        val sources = root.resolve("android-compose/example/src/commonMain/kotlin")
        sources.createDirectories()
        sources.resolve("Example.kt").writeText(
            """
            package example
            // class Phantom
            class Outer {
                class Nested
                fun method() { class Local }
            }
            interface Contract
            object Singleton
            internal fun hidden() = Unit
            fun publicEntry() = Unit
            """.trimIndent(),
        )
        StructureSourceExport.write(root, Konsist.scopeFromExternalDirectories(listOf(sources.toString())).files)
        val lines = root.resolve("android-compose/build/reports/structure/sources.jsonl").readLines()
        val types = lines.filter { it.contains("\"type\":\"type\"") }
        assertEquals(4, types.size)
        assertTrue(types.any { it.contains("example.Outer.Nested") })
        assertTrue(types.none { it.contains("Phantom") || it.contains("Local") })
        val public = lines.filter { it.contains("\"type\":\"public\"") }
        assertTrue(public.any { it.contains("publicEntry") })
        assertTrue(public.none { it.contains("hidden") || it.contains("method") })
    }

    @Test
    fun `record escapes path and content characters`() {
        assertEquals("{\"type\":\"file\",\"path\":\"a\\\\b\\n\\\"\"}", StructureSourceExport.record("file", "path" to "a\\b\n\""))
    }
}
