package com.letta.mobile.desktop.input

import com.sun.jna.Native
import com.sun.jna.Pointer
import java.awt.Component
import java.awt.Window

/**
 * Manages open native tablet service handles against window component targets.
 */
internal class TabletPenConnection(private val window: Window) {
    val handles = mutableListOf<Triple<String, Long, Component>>()
    var sawFrom: String? = null
        internal set

    val isConnected: Boolean get() = handles.isNotEmpty()

    fun openTargets(): Boolean {
        val targets = findNativeTargets(window)
        targets.forEach { (component, address) ->
            println("TABLET: candidate ${component::class.java.name} handle=$address")
        }
        if (targets.isEmpty()) {
            println("TABLET: no native handle anywhere")
            return false
        }
        targets.forEach { (component, address) ->
            val opened = runCatching { TabletBridge.nativeOpen(address) }
                .onFailure { println("TABLET: nativeOpen threw for ${component.label()}: $it") }
                .getOrDefault(0L)
            if (opened != 0L) {
                handles += Triple(component.label(), opened, component)
                println("TABLET: listening on ${component.label()} ($address)")
            } else {
                println("TABLET: ${component.label()} has no tablet service")
            }
        }
        return handles.isNotEmpty()
    }

    fun dropDeadTargets() {
        val dead = handles.filterNot { (_, _, component) -> component.isDisplayable }
        if (dead.isEmpty()) return
        handles.removeAll(dead)
        dead.forEach { (name, address, _) ->
            println("TABLET: $name is gone; closing its handle")
            runCatching { TabletBridge.nativeClose(address) }
        }
        if (handles.isEmpty()) sawFrom = null
    }

    fun close() {
        val open = handles.toList()
        handles.clear()
        sawFrom = null
        open.forEach { (_, address, _) -> runCatching { TabletBridge.nativeClose(address) } }
    }

    private fun findNativeTargets(window: Window): List<Pair<Component, Long>> {
        val found = mutableListOf<Pair<Component, Long>>()
        fun visit(component: Component) {
            val address = runCatching { Native.getComponentPointer(component) }
                .getOrNull()
                ?.let { Pointer.nativeValue(it) }
                ?.takeIf { it != 0L }
            if (address != null) found += component to address
            if (component is java.awt.Container) component.components.forEach { visit(it) }
        }
        visit(window)
        return found
    }
}
