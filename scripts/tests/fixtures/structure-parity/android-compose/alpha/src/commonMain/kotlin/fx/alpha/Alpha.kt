@file:Suppress("unused")

package fx.alpha

import fx.alpha.Marker
import fx.beta.Beta
import fx.beta.Beta.Inner
import fx.beta.helper
import fx.beta.sub.*
import fx.gamma.Thing as Renamed
import kotlinx.coroutines.flow.Flow

// class CommentedOut
/* interface AlsoCommented { /* nested */ class Deep } */
private const val TEXT = "class InString { object Nope }"
private const val BRACE = '{'
private val template = "${object : Any() { val y = "}" }}"

expect class Platform

class Outer(
    val callback: () -> Unit = {},
) : Base() {
    class Nested
    inner class InnerOne
    companion object {
        const val X = 1
    }

    fun work() {
        class LocalOne
        val anon = object : Runnable {
            override fun run() = Unit
        }
    }
}

open class Base

data object Marker

class Injected
@Inject
constructor(
    private val dep: String,
) {
    object Holder
}

class Bodiless(val x: Int)

fun later() {
    class LocalTwo
}

sealed interface State {
    data object Idle : State
    data class Busy(val n: Int) : State
}

enum class Mode {
    ON { override fun flip() = OFF },
    OFF { override fun flip() = ON };

    abstract fun flip(): Mode

    class InsideEnum
}

fun interface Callback {
    fun invoke()
}

class Generic<T : Any> where T : Comparable<T> {
    companion object Factory
}

val typed = Marker::class
annotation class Tag
