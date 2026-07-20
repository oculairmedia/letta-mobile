package com.letta.mobile.architecture

import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

@CacheableTask
abstract class ArchitectureGraphTask : DefaultTask() {
    @get:Input
    abstract val records: ListProperty<String>

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun writeGraph() {
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()
        output.writeText(records.get().distinct().sorted().joinToString(separator = "\n", postfix = "\n"))
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
