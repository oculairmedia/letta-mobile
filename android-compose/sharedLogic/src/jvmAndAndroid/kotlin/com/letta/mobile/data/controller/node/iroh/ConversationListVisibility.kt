package com.letta.mobile.data.controller.node.iroh

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * letta-mobile-fxoew.6: a wire conversation row the store marked `"hidden": true`
 * (subagent and background conversations). Only a strict JSON boolean counts,
 * the same reading the App Server's `include_hidden` filter applies.
 */
internal fun JsonObject.isHiddenConversation(): Boolean =
    (this["hidden"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull == true
