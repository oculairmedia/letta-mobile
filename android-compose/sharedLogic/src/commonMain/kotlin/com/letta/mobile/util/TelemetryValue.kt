package com.letta.mobile.util

/** Attribute values retain their scalar or flat numeric-list types and diagnostic rendering. */
sealed interface TelemetryValue {
    data object Null : TelemetryValue {
        override fun toString(): String = "null"
    }

    data class Text(val value: String) : TelemetryValue {
        override fun toString(): String = value
    }

    data class Numeric(val value: Number) : TelemetryValue {
        override fun toString(): String = value.toString()
    }

    data class NumericList(val value: List<Number>) : TelemetryValue {
        override fun toString(): String = value.toString()
    }

    data class Flag(val value: Boolean) : TelemetryValue {
        override fun toString(): String = value.toString()
    }
}

infix fun String.attr(value: String?): Pair<String, TelemetryValue> =
    this to (value?.let(TelemetryValue::Text) ?: TelemetryValue.Null)

infix fun String.attr(value: Number?): Pair<String, TelemetryValue> =
    this to (value?.let(TelemetryValue::Numeric) ?: TelemetryValue.Null)

infix fun String.attr(value: List<Number>): Pair<String, TelemetryValue> =
    this to TelemetryValue.NumericList(value)

infix fun String.attr(value: Boolean?): Pair<String, TelemetryValue> =
    this to (value?.let(TelemetryValue::Flag) ?: TelemetryValue.Null)

infix fun String.attr(value: TelemetryValue): Pair<String, TelemetryValue> = this to value

val TelemetryValue.textOrNull: String? get() = (this as? TelemetryValue.Text)?.value
val TelemetryValue.numberOrNull: Number? get() = (this as? TelemetryValue.Numeric)?.value
val TelemetryValue.booleanOrNull: Boolean? get() = (this as? TelemetryValue.Flag)?.value

fun Iterable<Pair<String, String?>>.telemetryAttributes(): Array<Pair<String, TelemetryValue>> =
    map { (key, value) -> key attr value }.toTypedArray()

fun Array<out Pair<String, String?>>.telemetryAttributes(): Array<Pair<String, TelemetryValue>> =
    map { (key, value) -> key attr value }.toTypedArray()
