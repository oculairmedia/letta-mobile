package com.letta.mobile.runtime.local

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** LM Studio's native model list, which letta-code's `lmstudio` provider asks for first. */
internal const val LM_STUDIO_MODELS_PATH = "/api/v0/models"

/**
 * letta-mobile-by4vm.121: the bridge's model in LM Studio's `/api/v0/models` shape.
 *
 * letta-code 0.34.14 (`lmStudioDiscover` → `parseLmStudioModels`) marks a model image-capable only
 * from this list (`type: "vlm"` or `capabilities` containing `vision`); otherwise it replaces every
 * image with "(image omitted: model does not support images)" before calling the bridge. The
 * capability comes from the model catalog ([OnDeviceModelCapability.supportsImages]).
 *
 * No `loaded_context_length` / `max_context_length` (by4vm.121.16 follow-up): letta-code would take
 * it as the model's context window, and its pi-ai layer keeps a fixed 4,096-token safety margin
 * (`clampMaxTokensToContext`: window - prompt estimate - 4,096), so a 4,096- or 8,192-token engine
 * window clamps every request's output to 1 token (seen on a Pixel 9 Pro: "Okay", then
 * `max_tokens_exceeded`). letta-code keeps its default window; the bridge enforces the engine's real
 * one itself, before LiteRT-LM sees the request ([OnDeviceContextBudget]), with a loud overflow error.
 */
internal fun lmStudioModelsBody(servedModelId: String, capability: OnDeviceModelCapability): JsonObject =
    buildJsonObject {
        put("object", "list")
        put(
            "data",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("id", servedModelId)
                        put("object", "model")
                        put("type", if (capability.supportsImages == true) "vlm" else "llm")
                        put("publisher", "letta-mobile")
                        put("state", "loaded")
                        put(
                            "capabilities",
                            buildJsonArray { if (capability.supportsImages == true) add(JsonPrimitive("vision")) },
                        )
                    },
                )
            },
        )
    }
