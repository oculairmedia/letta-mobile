package com.letta.mobile.web

import com.letta.mobile.data.shell.ShellWorkspaceState
import kotlinx.browser.window
import org.w3c.dom.events.Event

/**
 * letta-mobile-ckmub.17.3: the browser actual of the shell's route. The URL fragment is the shared
 * workspace's canonical [ShellWorkspaceState.route] (ids stay out of the query string); writing it
 * adds a history entry, and the browser's back and forward ([followUrl] on `hashchange`) move the
 * workspace between routes. Nothing here decides a route: [ShellWorkspaceState.openRoute] does.
 */
internal class BrowserRouteBinding(private val workspace: ShellWorkspaceState) {
    private val onHashChange: (Event) -> Unit = { followUrl() }

    /** Opens the route the page was loaded with, then follows back and forward; returns the way to stop. */
    fun start(): () -> Unit {
        followUrl()
        window.addEventListener("hashchange", onHashChange)
        return { window.removeEventListener("hashchange", onHashChange) }
    }

    /** Shows [route] in the address bar unless it is already there. */
    fun publish(route: String) {
        if (currentRoute() != route) window.location.hash = route
    }

    private fun followUrl() {
        val route = currentRoute()
        if (route.isNotEmpty() && route != workspace.route) workspace.openRoute(route)
    }

    private fun currentRoute(): String = window.location.hash.removePrefix("#")
}
