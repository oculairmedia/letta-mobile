package com.letta.mobile.data.timeline

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap

internal fun <T> Iterable<T>.toTimelinePersistentList(): PersistentList<T> =
    toPersistentList()

internal fun <K, V> Map<K, V>.toTimelinePersistentMap(): PersistentMap<K, V> =
    toPersistentMap()
