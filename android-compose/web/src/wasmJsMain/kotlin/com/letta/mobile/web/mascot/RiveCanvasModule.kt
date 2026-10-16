@file:JsModule("@rive-app/canvas-advanced")
@file:OptIn(ExperimentalWasmJsInterop::class)

package com.letta.mobile.web.mascot

/**
 * The low-level Rive runtime (`@rive-app/canvas-advanced`, pinned in web/build.gradle.kts and
 * bundled by the web build): resolves to the runtime once its wasm, located by `options.locateFile`,
 * is instantiated.
 */
@JsName("default")
internal external fun initRiveCanvas(options: JsAny): kotlin.js.Promise<JsAny>
