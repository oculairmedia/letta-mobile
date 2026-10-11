package com.letta.mobile.data.meridian

/**
 * What can be wrong with the argv words or stdin of a command, before any tool runs
 * (letta-mobile-jna0o.3). Each problem knows its code, its message and, when the input is at
 * fault, the JSON pointer of the property it is about.
 */
internal sealed interface MeridianInputProblem {
    val code: MeridianErrorCode
    val message: String
    val pointer: String? get() = null

    data class MissingValue(val flag: String) : MeridianInputProblem {
        override val code get() = MeridianErrorCode.USAGE
        override val message get() = "$flag needs a value"
    }

    data class UnexpectedArgument(val word: String) : MeridianInputProblem {
        override val code get() = MeridianErrorCode.USAGE
        override val message get() = "unexpected argument '$word'"
    }

    data class GivenTwice(val property: MeridianInputProperty) : MeridianInputProblem {
        override val code get() = MeridianErrorCode.INVALID_INPUT
        override val message get() = "${property.name} is given both in the stdin JSON and as an argument"
        override val pointer get() = property.pointer
    }

    data class UnknownFlag(val command: MeridianCommand, val property: MeridianInputProperty) : MeridianInputProblem {
        override val code get() = MeridianErrorCode.INVALID_INPUT
        override val message get() = "${command.display} takes no ${property.flag}"
        override val pointer get() = property.pointer
    }

    data class WrongType(val property: MeridianInputProperty) : MeridianInputProblem {
        override val code get() = MeridianErrorCode.INVALID_INPUT
        override val message get() = "${property.flag} must be ${property.expected()}"
        override val pointer get() = property.pointer
    }

    data class InvalidJson(val parserMessage: String?) : MeridianInputProblem {
        override val code get() = MeridianErrorCode.INVALID_JSON
        override val message get() =
            parserMessage?.let { "stdin is not valid JSON: ${it.lineSequence().first()}" } ?: "stdin must be one JSON object"
        override val pointer get() = ""
    }

    /** The structured error for this problem in [command], with the hint stderr carries. */
    fun toError(command: MeridianCommand): MeridianError =
        MeridianError(code, message, command = command.display, pointer = pointer, hint = hint(command))

    private fun hint(command: MeridianCommand): String? = when (code) {
        MeridianErrorCode.INVALID_JSON ->
            "Pass the input as one JSON object on stdin with a quoted heredoc: meridian ${command.display} <<'JSON' ... JSON"
        MeridianErrorCode.USAGE, MeridianErrorCode.INVALID_INPUT -> "See: meridian ${command.display} --help"
        else -> null
    }
}
