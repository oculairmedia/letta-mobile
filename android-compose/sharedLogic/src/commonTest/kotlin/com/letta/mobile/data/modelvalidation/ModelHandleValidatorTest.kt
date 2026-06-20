package com.letta.mobile.data.modelvalidation

import kotlin.test.Test
import kotlin.test.assertIs

class ModelHandleValidatorTest {
    @Test
    fun litertUnderLmstudioRemoteIsInvalid() {
        val result = ModelHandleValidator.validate(
            handle = "lmstudio/google/gemma-3n-E2B-it-litert-lm",
            backend = ModelHandleValidator.Backend.REMOTE,
        )

        assertIs<ModelHandleValidator.Result.Invalid>(result)
    }

    @Test
    fun servedLmstudioRemoteModelIsValid() {
        val result = ModelHandleValidator.validate(
            handle = "lmstudio/deepseek-v4-flash",
            backend = ModelHandleValidator.Backend.REMOTE,
            servedModels = listOf("deepseek-v4-flash", "gpt-4.1"),
        )

        assertIs<ModelHandleValidator.Result.Valid>(result)
    }

    @Test
    fun unservedLmstudioRemoteModelIsInvalid() {
        val result = ModelHandleValidator.validate(
            handle = "lmstudio/some-unserved",
            backend = ModelHandleValidator.Backend.REMOTE,
            servedModels = listOf("deepseek-v4-flash", "gpt-4.1"),
        )

        assertIs<ModelHandleValidator.Result.Invalid>(result)
    }

    @Test
    fun properOnDeviceLitertSelectionIsValid() {
        val result = ModelHandleValidator.validate(
            handle = "google/gemma-3n-E2B-it-litert-lm",
            backend = ModelHandleValidator.Backend.ON_DEVICE,
            onDeviceModelPath = "/data/user/0/com.letta.mobile/files/models/gemma.litertlm",
        )

        assertIs<ModelHandleValidator.Result.Valid>(result)
    }
}
