package com.letta.mobile.desktop

import com.letta.mobile.data.model.LettaConfig
import com.letta.mobile.desktop.data.DesktopDataBindings
import com.letta.mobile.desktop.data.createDefaultDesktopDataBindings

data class DesktopBootstrapState(
    val config: LettaConfig,
    val sessionGraphId: Long,
    val featureReadiness: List<DesktopFeatureReadiness>,
)

data class DesktopFeatureReadiness(
    val title: String,
    val description: String,
    val state: DesktopFeatureState,
)

enum class DesktopFeatureState {
    Ready,
    InProgress,
    AndroidOnly,
}

enum class DesktopDestination(
    val label: String,
    val summary: String,
) {
    Overview(
        label = "Overview",
        summary = "Windows desktop launch status and backend configuration.",
    ),
    Agents(
        label = "Agents",
        summary = "Shared agent models are available; repository and persistence wiring still need desktop implementations.",
    ),
    Conversations(
        label = "Conversations",
        summary = "Desktop chat uses a persistent conversation list, shared render models, and a JVM Compose detail pane.",
    ),
    Settings(
        label = "Settings",
        summary = "Desktop settings will use JVM storage instead of Android DataStore and encrypted preferences.",
    ),
}

fun defaultDesktopBootstrapState(
    dataBindings: DesktopDataBindings = createDefaultDesktopDataBindings(),
) = DesktopBootstrapState(
    config = LettaConfig(
        id = "desktop-local",
        mode = LettaConfig.Mode.SELF_HOSTED,
        serverUrl = "http://localhost:8283",
        accessToken = null,
    ),
    sessionGraphId = dataBindings.sessionGraphProvider.current.id,
    featureReadiness = listOf(
        DesktopFeatureReadiness(
            title = "Windows desktop runtime",
            description = "Compose Desktop boots from Gradle with EXE and MSI package tasks configured.",
            state = DesktopFeatureState.Ready,
        ),
        DesktopFeatureReadiness(
            title = "Shared Letta models",
            description = "The app consumes :sharedLogic so data contracts match Android.",
            state = DesktopFeatureState.Ready,
        ),
        DesktopFeatureReadiness(
            title = "Desktop repository layer",
            description = "Desktop can construct a shared session graph with JVM settings and health adapters; concrete remote repositories are still pending.",
            state = DesktopFeatureState.InProgress,
        ),
        DesktopFeatureReadiness(
            title = "Desktop chat surface",
            description = "Conversation list, detail pane, shared run-block rows, tool cards, A2UI payload cards, and local composer queue are available in the Windows shell.",
            state = DesktopFeatureState.Ready,
        ),
        DesktopFeatureReadiness(
            title = "Android app shell",
            description = "Current navigation, Hilt wiring, notifications, and DataStore remain Android-only.",
            state = DesktopFeatureState.AndroidOnly,
        ),
    ),
)
