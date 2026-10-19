package com.letta.mobile.architecture

import com.lemonappdev.konsist.api.declaration.KoClassDeclaration
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.declaration.KoInterfaceDeclaration
import com.lemonappdev.konsist.api.declaration.KoObjectDeclaration
import com.lemonappdev.konsist.api.provider.KoFullyQualifiedNameProvider
import com.lemonappdev.konsist.api.provider.KoNameProvider
import com.lemonappdev.konsist.api.provider.modifier.KoVisibilityModifierProvider
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Export parsed declarations and the existing import graph, without compiling platform sources. */
internal object StructureSourceExport {
    fun write(root: Path, files: List<KoFileDeclaration>) {
        check(files.isNotEmpty()) { "No production Kotlin files: refusing an empty structure report" }
        val output = root.resolve("android-compose/build/reports/structure")
        output.createDirectories()
        val records = files.sortedBy { it.path }.flatMap { file -> fileRecords(root, file) }
        val imports = files.map { SourceFileImports(it.packagee?.name.orEmpty(), it.imports.map { item -> item.name }) }
        val edges = PackageImportGraph.edges(imports).sortedWith(compareBy({ it.first }, { it.second }))
            .map { (from, to) -> record("importEdge", "from" to from, "to" to to) }
        output.resolve("sources.jsonl").writeText((records + edges).joinToString("\n", postfix = "\n"))
    }

    private fun fileRecords(root: Path, file: KoFileDeclaration): List<String> {
        val relative = root.relativize(Path.of(file.path)).toString().replace('\\', '/')
        val module = relative.substringAfter("android-compose/").substringBefore("/src/")
        val packageName = file.packagee?.name.orEmpty()
        check(packageName.isNotEmpty()) { "$relative has no named package" }
        val attributes = arrayOf("path" to relative, "module" to module, "package" to packageName)
        return buildList {
            // Textual mentions deliberately over-count comments/strings. A zero is a review
            // candidate, never proof of non-use: reflection and external consumers are invisible.
            val mentions = Regex("[A-Za-z_][A-Za-z_0-9]*").findAll(file.text).map { it.value }.toSortedSet()
            add(record("file", *attributes, "mentions" to mentions.joinToString(" ")))
            file.declarations(includeNested = true, includeLocal = false).forEach { declaration ->
                val kind = when (declaration) {
                    is KoClassDeclaration -> "class"
                    is KoInterfaceDeclaration -> "interface"
                    is KoObjectDeclaration -> "object"
                    else -> null
                }
                if (kind != null) {
                    val name = (declaration as KoFullyQualifiedNameProvider).fullyQualifiedName ?: return@forEach
                    add(record("type", *attributes, "name" to name, "kind" to kind))
                }
            }
            file.declarations(includeNested = false, includeLocal = false).forEach { declaration ->
                val visibility = declaration as? KoVisibilityModifierProvider
                val name = (declaration as? KoNameProvider)?.name
                if (visibility?.hasPublicOrDefaultModifier == true && name != null) {
                    add(record("public", *attributes, "name" to name))
                }
            }
        }
    }

    internal fun record(type: String, vararg fields: Pair<String, String>): String =
        (listOf("type" to type) + fields).joinToString(",", prefix = "{", postfix = "}") { (key, value) ->
            "${quote(key)}:${quote(value)}"
        }

    private fun quote(text: String): String = buildString {
        append('"')
        text.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }
}
