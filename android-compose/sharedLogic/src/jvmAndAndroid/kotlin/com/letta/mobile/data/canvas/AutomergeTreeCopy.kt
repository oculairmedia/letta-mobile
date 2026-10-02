package com.letta.mobile.data.canvas

import java.util.Date
import org.automerge.AmValue
import org.automerge.NewValue
import org.automerge.ObjectId
import org.automerge.ObjectType
import org.automerge.Read
import org.automerge.Transaction

/**
 * Copies values out of [source] into the document [tx] writes, as plain current state: the copy
 * carries no history. Text marks are not copied; notebook text carries none.
 */
internal class AutomergeTreeCopy(private val source: Read, private val tx: Transaction) {
    /** Copy one value into map [to] at [key]. */
    fun copyValue(value: AmValue, to: ObjectId, key: String) {
        when (value) {
            is AmValue.Map -> copyMap(value.id, tx.set(to, key, ObjectType.MAP))
            is AmValue.List -> copyList(value.id, tx.set(to, key, ObjectType.LIST))
            is AmValue.Text -> tx.spliceText(tx.set(to, key, ObjectType.TEXT), 0, 0, source.text(value.id).orElseThrow())
            else -> tx.set(to, key, scalar(value))
        }
    }

    /** Copy [source]'s map entry [key] of [from] into map [to] under the same key. */
    fun copyEntry(from: ObjectId, to: ObjectId, key: String) = copyValue(source.get(from, key).orElseThrow(), to, key)

    /** Copy a whole object tree. */
    fun copyMap(from: ObjectId, to: ObjectId) {
        for (key in source.keys(from).orElseThrow()) copyEntry(from, to, key)
    }

    private fun copyList(from: ObjectId, to: ObjectId) {
        source.listItems(from).orElseThrow().forEachIndexed { index, value -> insertValue(value, to, index.toLong()) }
    }

    private fun insertValue(value: AmValue, to: ObjectId, at: Long) {
        when (value) {
            is AmValue.Map -> copyMap(value.id, tx.insert(to, at, ObjectType.MAP))
            is AmValue.List -> copyList(value.id, tx.insert(to, at, ObjectType.LIST))
            is AmValue.Text -> tx.spliceText(tx.insert(to, at, ObjectType.TEXT), 0, 0, source.text(value.id).orElseThrow())
            else -> tx.insert(to, at, scalar(value))
        }
    }

    private companion object {
        fun scalar(value: AmValue): NewValue = when (value) {
            is AmValue.Str -> NewValue.str(value.value)
            is AmValue.Int -> NewValue.integer(value.value)
            is AmValue.UInt -> NewValue.uint(value.value)
            is AmValue.F64 -> NewValue.f64(value.value)
            is AmValue.Bool -> NewValue.bool(value.value)
            is AmValue.Bytes -> NewValue.bytes(value.value)
            is AmValue.Counter -> NewValue.counter(value.value)
            is AmValue.Timestamp -> NewValue.timestamp(Date(value.value.time))
            is AmValue.Null -> NewValue.NULL
            // Refuse rather than drop a value this version cannot write back.
            else -> error("Cannot copy Automerge value ${value::class.java.simpleName}")
        }
    }
}
