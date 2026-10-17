@file:JsModule("letta-rive-frames")
@file:OptIn(ExperimentalWasmJsInterop::class)

package com.letta.mobile.web.mascot

import kotlin.js.Promise

/*
 * The browser binding (web/src/wasmJsMain/js/rive-frames/index.mjs, bundled by the web build over
 * the pinned @rive-app/canvas-advanced): runtime bring-up, one OffscreenCanvas scene per mascot,
 * its readback and its view-model writes. Scene handles are opaque JS objects.
 */

@JsName("initRuntime")
internal external fun riveInitRuntime(wasmUrl: String): Promise<JsAny>

@JsName("fetchBytes")
internal external fun riveFetchBytes(url: String): Promise<JsAny>

@JsName("loadFile")
internal external fun riveLoadFile(rive: JsAny, bytes: JsAny): Promise<JsAny>

@JsName("fingerprint")
internal external fun riveFingerprint(bytes: JsAny): String

@JsName("createScene")
internal external fun riveCreateScene(rive: JsAny, file: JsAny, stateMachine: String): JsAny

@JsName("render")
internal external fun riveRender(scene: JsAny, width: Int, height: Int): JsAny

@JsName("copyIntoHeap")
internal external fun riveCopyIntoHeap(skiko: JsAny, pixels: JsAny, address: Int): Boolean

@JsName("pixelByte")
internal external fun rivePixelByte(pixels: JsAny, index: Int): Int

@JsName("advance")
internal external fun riveAdvance(scene: JsAny, seconds: Float)

@JsName("pointer")
internal external fun rivePointer(scene: JsAny, kind: Int, x: Float, y: Float)

@JsName("close")
internal external fun riveClose(scene: JsAny)

@JsName("setNumber")
internal external fun riveSetNumber(scene: JsAny, name: String, value: Float)

@JsName("setBoolean")
internal external fun riveSetBoolean(scene: JsAny, name: String, value: Boolean)

@JsName("setEnum")
internal external fun riveSetEnum(scene: JsAny, name: String, key: String)

@JsName("setColor")
internal external fun riveSetColor(scene: JsAny, name: String, argb: Int)

@JsName("fire")
internal external fun riveFire(scene: JsAny, name: String)
