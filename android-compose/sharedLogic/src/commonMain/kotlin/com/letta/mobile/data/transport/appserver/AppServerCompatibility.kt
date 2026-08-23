package com.letta.mobile.data.transport.appserver

/** Protocol and capability contract a controller requires before publishing a client generation. */
data class AppServerCompatibilityRequirement(
    val protocolVersion: Int,
    val expectedBackend: String? = null,
    val requiredCapabilities: Set<String> = emptySet(),
    val requiredDisabledCapabilities: Set<String> = emptySet(),
)

fun AppServerInfoData.requireCompatibleWith(
    requirement: AppServerCompatibilityRequirement,
): AppServerInfoData {
    check(!lettaCodeVersion.isNullOrBlank()) {
        "App Server handshake omitted letta_code_version"
    }
    check(protocolVersion == requirement.protocolVersion) {
        "Unsupported App Server protocol_version=$protocolVersion; expected ${requirement.protocolVersion}"
    }
    val actualBackend = backend?.trim()?.takeIf { it.isNotEmpty() }
        ?: error("App Server handshake omitted backend")
    requirement.expectedBackend?.let { expected ->
        check(actualBackend.equals(expected, ignoreCase = true)) {
            "App Server backend '$actualBackend' does not match expected '$expected'"
        }
    }
    check(capabilities != null) {
        "App Server handshake omitted capabilities"
    }
    val missing = requirement.requiredCapabilities.filter { capability(it) != true }
    check(missing.isEmpty()) {
        "App Server is missing required capabilities: ${missing.sorted().joinToString(", ")}"
    }
    val enabled = requirement.requiredDisabledCapabilities.filter { capability(it) != false }
    check(enabled.isEmpty()) {
        "App Server does not explicitly disable incompatible capabilities: " +
            enabled.sorted().joinToString(", ")
    }
    return this
}
