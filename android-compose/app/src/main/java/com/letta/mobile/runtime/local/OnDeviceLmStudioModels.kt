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
 * `max_context_length` is left out on purpose: letta-code would take it as the model's context
 * window, and the engine's (the Settings "Max tokens", 4096 by default) is far below the ~20k
 * tokens of letta-code's own system prompt and tools, so every turn would fail its prompt-floor
 * check ("Raise the engine's context window"). The bridge re-renders a compact prompt that fits
 * the engine ([OnDeviceToolCallProtocol.renderPrompt]); letta-code keeps its default window.
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
