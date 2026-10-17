@file:JsModule("./skiko.mjs")
@file:OptIn(ExperimentalWasmJsInterop::class)

package com.letta.mobile.web.mascot

/**
 * Skiko's loaded wasm instance (`_` holds its exports, `memory` among them): the module the
 * Compose runtime itself imports. A rendered frame is copied into a Skia bitmap's pixels in that
 * memory with one typed-array copy, rather than byte by byte across the wasm boundary.
 */
internal external val loadedWasm: JsAny
