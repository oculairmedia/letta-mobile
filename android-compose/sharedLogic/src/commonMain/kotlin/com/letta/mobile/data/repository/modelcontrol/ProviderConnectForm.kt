package com.letta.mobile.data.repository.modelcontrol

/**
 * Platform-neutral state of a provider connect form: which auth method is
 * chosen and what the user typed. Both the Android and desktop dialogs render
 * this; neither re-implements validation or request building.
 */
data class ProviderConnectForm(
    val provider: ConnectableProvider,
    val methodIndex: Int = 0,
    val values: Map<String, String> = emptyMap(),
) {
    val method: ProviderAuthMethod? get() = provider.authMethods.getOrNull(methodIndex)

    val fields: List<ProviderField> get() = method?.fields.orEmpty()

    /** Required fields still blank; the submit action stays disabled while any remain. */
    val missingRequired: List<ProviderField>
        get() = fields.filter { it.required && value(it).isBlank() }

    val canSubmit: Boolean get() = provider.canConnectFromApp && method != null && missingRequired.isEmpty()

    fun value(field: ProviderField): String = values[field.key].orEmpty()

    fun withValue(key: String, value: String): ProviderConnectForm = copy(values = values + (key to value))

    /** Switching method keeps values for keys both methods share (e.g. `region`). */
    fun withMethod(index: Int): ProviderConnectForm {
        val next = copy(methodIndex = index.coerceIn(0, (provider.authMethods.size - 1).coerceAtLeast(0)))
        val keys = next.fields.map { it.key }.toSet()
        return next.copy(values = values.filterKeys { it in keys })
    }

    /** Blank optional fields are omitted so the App Server applies its own default. */
    fun toRequest(): ProviderConnectRequest {
        check(canSubmit) { "form is not submittable" }
        val filled = fields.associate { it.key to value(it).trim() }.filterValues { it.isNotEmpty() }
        return ProviderConnectRequest(provider.id, method?.id, filled)
    }

    override fun toString(): String =
        "ProviderConnectForm(provider=${provider.id}, methodIndex=$methodIndex, filledKeys=${values.keys.sorted()})"
}
