package com.letta.mobile.fixture

class ImplicitJvm {
    private val lock = Any()

    fun now(): Long = System.currentTimeMillis()

    fun bigger(a: Int, b: Int): Int = Math.max(a, b)

    fun name(): String = ImplicitJvm::class.java.name

    fun locked(): Int = synchronized(lock) { 1 }
}
