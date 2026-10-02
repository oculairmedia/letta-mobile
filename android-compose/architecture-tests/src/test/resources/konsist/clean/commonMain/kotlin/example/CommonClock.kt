package com.letta.mobile.fixture

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Common replacements for java.lang: a kotlin.time clock and atomicfu's lock. */
class CommonClock {
    private val lock = SynchronizedObject()

    // Not System.currentTimeMillis(): that is JVM-only.
    fun now(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    fun locked(): Int = synchronized(lock) { 1 }
}
