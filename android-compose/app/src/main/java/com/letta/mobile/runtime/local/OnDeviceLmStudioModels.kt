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
 * `loaded_context_length` is the engine's real context window (the Settings "Max tokens",
 * [EmbeddedLettaCodeModelSelection.maxTokens]), letta-mobile-by4vm.121.16. The bridge no longer
 * shrinks letta-code's prompt to fit, so letta-code must know the window: its own prompt-floor
 * check then fails loudly ("The system prompt and tool definitions need about N tokens, but … is
 * served with a 4,096-token context window") when an agent's prompt and tools cannot fit, and
 * its context management compacts history against the true window.
 */
internal fun lmStudioModelsBody(servedModelId: String, capability: OnDeviceModelCapability, contextLength: Int): JsonObject =
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
                        put("loaded_context_length", contextLength)
                        put("max_context_length", contextLength)
                        put(
                            "capabilities",
                            buildJsonArray { if (capability.supportsImages == true) add(JsonPrimitive("vision")) },
                        )
                    },
                )
            },
        )
    }
