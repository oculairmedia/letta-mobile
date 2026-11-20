package com.letta.mobile.ui.modelcontrol

import com.letta.mobile.data.servicecredentials.ServiceConfigField
import com.letta.mobile.data.servicecredentials.ServiceCredentialDraft
import com.letta.mobile.data.servicecredentials.ServiceCredentialErrorCode
import com.letta.mobile.data.servicecredentials.ServiceCredentialStatus
import com.letta.mobile.data.servicecredentials.ServiceCredentialsState
import com.letta.mobile.data.servicecredentials.ServiceId
import com.letta.mobile.data.servicecredentials.ServiceTestState
import com.letta.mobile.data.servicecredentials.ServiceTestStatus

/** Fake services for renders and UI tests: no real key, address or account. */
internal object ServiceCredentialsUiFixtures {
    val jev = ServiceCredentialStatus(
        serviceId = ServiceId("typesafe-jev"),
        displayName = "TypeSafe Jev",
        summary = "Typed judgements (score, choice, yes/no) for host features such as the voice mood gate.",
        configured = true,
        defaultBaseUrl = "https://api.typesafe.ai",
        hasSecret = true,
        secretLabel = "API key",
        configFields = listOf(ServiceConfigField("model", "Model", "The Jev model or alias requests use.", "jev-latest")),
        config = mapOf("model" to "jev-latest"),
        revision = "rev-2",
        lastTest = ServiceTestStatus(ServiceTestState.PASSED, 1_767_225_600_000L),
    )

    val speech = ServiceCredentialStatus(
        serviceId = ServiceId("example-speech"),
        displayName = "Example speech server",
        summary = "A made-up service with an address of its own, for layout only.",
        baseUrlEditable = true,
        secretLabel = "Token",
    )

    val failed = jev.copy(
        serviceId = ServiceId("example-failed"),
        displayName = "Example refused key",
        lastTest = ServiceTestStatus(ServiceTestState.FAILED, 1_767_225_600_000L, ServiceCredentialErrorCode.UNAUTHORIZED),
    )

    fun state(editing: ServiceId? = null, canManage: Boolean = true) = ServiceCredentialsState(
        supported = true,
        canManage = canManage,
        services = listOf(jev, failed, speech),
        editing = editing,
    )

    class Recorder {
        val saved = mutableListOf<Pair<ServiceId, ServiceCredentialDraft>>()
        val tested = mutableListOf<ServiceId>()
        val edited = mutableListOf<ServiceId>()
        val actions = ServiceCredentialActions(
            onEdit = { edited += it },
            onCloseEdit = {},
            onSave = { id, draft -> saved += id to draft },
            onTest = { tested += it },
            onRemove = {},
            onRefresh = {},
        )
    }
}
