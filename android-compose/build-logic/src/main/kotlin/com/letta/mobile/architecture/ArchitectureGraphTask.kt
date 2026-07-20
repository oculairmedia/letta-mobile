package com.letta.mobile.architecture

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class ArchitectureGraphTask : DefaultTask() {
    @get:Input
    abstract val records: ListProperty<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @get:OutputDirectory
    abstract val contractDirectory: DirectoryProperty

    @TaskAction
    fun writeGraph() {
        val sortedRecords = records.get().distinct().sorted()
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(sortedRecords.joinToString(separator = "\n", postfix = "\n"))

        val contract = contractDirectory.get().asFile
        contract.mkdirs()
        CONTRACT_FILES.forEach { (fileName, recordTypes) ->
            val selected = sortedRecords.filter { line -> recordTypes.any { type -> line.contains("\"type\":\"$type\"") } }
            contract.resolve(fileName).writeText(selected.joinToString(separator = "\n", postfix = if (selected.isEmpty()) "" else "\n"))
        }
    }

    private companion object {
        val CONTRACT_FILES = mapOf(
            "modules.jsonl" to setOf("module"),
            "source_sets.jsonl" to setOf("sourceSet"),
            "variants.jsonl" to setOf("variant"),
            "project_edges.jsonl" to setOf("projectEdge"),
            "external_edges.jsonl" to setOf("externalDependency"),
        )
    }
}

internal object JsonLine {
    fun record(type: String, vararg fields: Pair<String, String?>): String {
        val values = sequenceOf("type" to type) + fields.asSequence()
        return values.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"${escape(key)}\":" + if (value == null) "null" else "\"${escape(value)}\""
        }
    }

    private fun escape(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character.code < 0x20) append("\\u%04x".format(character.code)) else append(character)
            }
        }
    }
}
