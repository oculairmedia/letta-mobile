package com.letta.mobile.data.model

object LocalAgentRuntimeMetadata {
    const val RuntimeKey = "runtime"
    const val RuntimeProviderKey = "runtime_provider"
    const val RuntimeIdKey = "runtime_id"
    const val LocalModelHandleKey = "local_model_handle"
    const val LocalModelRuntimeKey = "local_model_runtime"
    const val LocalModelAcceleratorKey = "local_model_accelerator"
    const val LocalLettaCodeRuntime = "local-lettacode"

    val bindingKeys: Set<String> = setOf(
        RuntimeKey,
        RuntimeIdKey,
        "runtimeId",
        RuntimeProviderKey,
        "runtimeProvider",
    )
}
