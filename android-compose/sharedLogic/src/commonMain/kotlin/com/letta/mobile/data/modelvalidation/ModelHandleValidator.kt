package com.letta.mobile.data.modelvalidation

object ModelHandleValidator {
    sealed interface Result {
        data object Valid : Result
        data class Invalid(val reason: String) : Result
    }

    enum class Backend { REMOTE, ON_DEVICE }

    data class Request(
        val handle: String,
        val backend: Backend,
        val customBaseUrl: String? = null,
        val onDeviceModelPath: String? = null,
        val servedModels: Collection<String>? = null,
    )

    fun validate(
        handle: String,
        backend: Backend,
        customBaseUrl: String? = null,
        onDeviceModelPath: String? = null,
        servedModels: Collection<String>? = null,
    ): Result = validate(
        Request(
            handle = handle,
            backend = backend,
            customBaseUrl = customBaseUrl,
            onDeviceModelPath = onDeviceModelPath,
            servedModels = servedModels,
        )
    )

    fun validate(request: Request): Result {
        val trimmedHandle = request.handle.trim()
        val trimmedBaseUrl = request.customBaseUrl?.trim().orEmpty()
        val routesRemote = request.backend == Backend.REMOTE ||
            trimmedBaseUrl.isNotBlank() ||
            trimmedHandle.startsWith(LMSTUDIO_PREFIX, ignoreCase = true)
        val modelId = trimmedHandle.toOpenAiModelId()

        if (routesRemote) {
            if (modelId.isLiteRtModelId()) {
                return Result.Invalid(
                    "LiteRT models are on-device, not remote — pick a served model or import a .litertlm."
                )
            }

            val servedModels = request.servedModels
            if (servedModels != null && servedModels.none { served -> served.matchesModelId(modelId) }) {
                val endpoint = trimmedBaseUrl.ifBlank { "the configured remote endpoint" }
                return Result.Invalid("$modelId is not served by $endpoint.")
            }

            return Result.Valid
        }

        if (modelId.isLiteRtModelId()) {
            val path = request.onDeviceModelPath?.trim().orEmpty()
            if (!path.endsWith(".litertlm", ignoreCase = true)) {
                return Result.Invalid(
                    "LiteRT models must use the on-device path — import or select a .litertlm model before saving."
                )
            }
        }

        return Result.Valid
    }

    fun String.toOpenAiModelId(): String = trim()
        .removePrefixIgnoreCase(LMSTUDIO_PREFIX)
        .removePrefixIgnoreCase("local/")
        .removePrefixIgnoreCase("llama-cpp/")
        .removePrefixIgnoreCase("llama.cpp/")

    private fun String.matchesModelId(modelId: String): Boolean = toOpenAiModelId().equals(modelId, ignoreCase = true)

    private fun String.isLiteRtModelId(): Boolean {
        val normalized = lowercase()
        return "litert" in normalized || normalized.endsWith("-litert-lm")
    }

    private fun String.removePrefixIgnoreCase(prefix: String): String =
        if (startsWith(prefix, ignoreCase = true)) substring(prefix.length) else this

    private const val LMSTUDIO_PREFIX = "lmstudio/"
}
