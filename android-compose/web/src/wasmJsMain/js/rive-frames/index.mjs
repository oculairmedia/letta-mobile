// letta-mobile-ckmub.17.6: the browser binding of the shared mascot frame pipeline.
// Kotlin (web/mascot/RiveCanvasRenderer.kt) declares these as externals; everything else - the
// director, gaze, presence, the frame loop and the copy into a Compose image - is shared Kotlin.
import RiveCanvas from '@rive-app/canvas-advanced';

/** The runtime, its wasm located at `wasmUrl` (staged beside the page; never a CDN). */
export function initRuntime(wasmUrl) {
  return RiveCanvas({ locateFile: () => wasmUrl });
}

export function fetchBytes(url) {
  return fetch(url).then((r) => {
    if (!r.ok) throw new Error('HTTP ' + r.status + ' for ' + url);
    return r.arrayBuffer();
  }).then((b) => new Uint8Array(b));
}

/** No asset loader and no Rive CDN: the mascot embeds everything it draws. */
export function loadFile(rive, bytes) {
  return rive.load(bytes, undefined, false);
}

/** FNV-1a over the file plus its length: a new asset means new stills. */
export function fingerprint(bytes) {
  let h = 0x811c9dc5;
  for (let i = 0; i < bytes.length; i++) {
    h ^= bytes[i];
    h = Math.imul(h, 0x01000193);
  }
  return (h >>> 0).toString(16).padStart(8, '0') + bytes.length.toString(16);
}

function pickStateMachine(artboard, name) {
  let definition = null;
  try { definition = artboard.stateMachineByName(name); } catch (e) { definition = null; }
  if (!definition && artboard.stateMachineCount() > 0) definition = artboard.stateMachineByIndex(0);
  return definition;
}

/** The artboard's view model as its authored default instance - the binding the desktop bridge makes. */
function defaultViewModel(file, artboard) {
  try {
    const vm = file.defaultArtboardViewModel(artboard);
    return vm ? (vm.defaultInstance() || vm.instance()) : null;
  } catch (e) {
    return null;
  }
}

/**
 * The file's default artboard with the named state machine (else its first) and its view model
 * bound to both, drawn by a Canvas2D renderer into an OffscreenCanvas kept for readback.
 */
export function createScene(rive, file, stateMachine) {
  const artboard = file.defaultArtboard();
  const definition = pickStateMachine(artboard, stateMachine);
  const machine = definition ? new rive.StateMachineInstance(definition, artboard) : null;
  const vmi = defaultViewModel(file, artboard);
  if (vmi) {
    artboard.bindViewModelInstance(vmi);
    if (machine) machine.bindViewModelInstance(vmi);
  }
  if (machine) machine.advanceAndApply(0); else artboard.advance(0);
  const canvas = new OffscreenCanvas(1, 1);
  const ctx = canvas.getContext('2d', { willReadFrequently: true });
  const renderer = rive.makeRenderer(canvas);
  return { rive, artboard, machine, vmi, canvas, ctx, renderer, inverse: null, w: 0, h: 0 };
}

function resize(s, w, h, frame) {
  const rive = s.rive;
  s.canvas.width = w;
  s.canvas.height = h;
  s.w = w;
  s.h = h;
  const view = rive.computeAlignment(rive.Fit.contain, rive.Alignment.center, frame, s.artboard.bounds);
  if (s.inverse) s.inverse.delete();
  const inverse = new rive.Mat2D();
  if (view.invert(inverse)) {
    s.inverse = inverse;
  } else {
    inverse.delete();
    s.inverse = null;
  }
  view.delete();
}

/** One frame at w x h as the canvas reads it back: straight (unpremultiplied) RGBA, top row first. */
export function render(s, w, h) {
  const rive = s.rive;
  const frame = { minX: 0, minY: 0, maxX: w, maxY: h };
  if (s.w !== w || s.h !== h) resize(s, w, h, frame);
  const r = s.renderer;
  if (r.beginFrame) r.beginFrame(true); else r.clear();
  r.save();
  r.align(rive.Fit.contain, rive.Alignment.center, frame, s.artboard.bounds);
  s.artboard.draw(r);
  r.restore();
  r.flush();
  rive.resolveAnimationFrame();
  return s.ctx.getImageData(0, 0, w, h).data;
}

/** One typed-array copy into Skia's wasm memory at `address`; false when that memory is not reachable. */
export function copyIntoHeap(skiko, pixels, address) {
  const memory = skiko && skiko._ && skiko._.memory;
  if (!memory) return false;
  new Uint8Array(memory.buffer, address, pixels.length).set(pixels);
  return true;
}

export function pixelByte(pixels, index) {
  return pixels[index];
}

export function advance(s, seconds) {
  if (s.machine) s.machine.advanceAndApply(seconds); else s.artboard.advance(seconds);
}

/** Render pixels to artboard space through the inverse of the last frame's alignment, then hit-test. */
export function pointer(s, kind, x, y) {
  if (!s.machine) return;
  let px = x;
  let py = y;
  if (s.inverse) {
    const p = new s.rive.Vec2D(x, y);
    const v = s.rive.mapXY(s.inverse, p);
    px = v.x();
    py = v.y();
    p.delete();
    v.delete();
  }
  if (kind === 0) s.machine.pointerMove(px, py, 0);
  else if (kind === 1) s.machine.pointerDown(px, py, 0);
  else if (kind === 2) s.machine.pointerUp(px, py, 0);
  else s.machine.pointerExit(px, py, 0);
}

export function close(s) {
  try {
    if (s.inverse) s.inverse.delete();
    if (s.machine) s.machine.delete();
    if (s.vmi) s.vmi.delete();
    s.artboard.delete();
    s.renderer.delete();
  } catch (e) {
    // Already released with the runtime; nothing left to free.
  }
}

// View-model writes. A property the file does not carry is skipped, as the desktop bridge skips it.

function withProperty(s, read, apply) {
  try {
    const p = s.vmi && read(s.vmi);
    if (p) apply(p);
  } catch (e) {
    // Not in this file's view model.
  }
}

export function setNumber(s, name, value) {
  withProperty(s, (vm) => vm.number(name), (p) => { p.value = value; });
}

export function setBoolean(s, name, value) {
  withProperty(s, (vm) => vm.boolean(name), (p) => { p.value = value; });
}

export function setEnum(s, name, key) {
  withProperty(s, (vm) => vm.enum(name), (p) => { p.value = key; });
}

export function setColor(s, name, argb) {
  withProperty(s, (vm) => vm.color(name), (p) => p.argb((argb >>> 24) & 255, (argb >>> 16) & 255, (argb >>> 8) & 255, argb & 255));
}

export function fire(s, name) {
  withProperty(s, (vm) => vm.trigger(name), (p) => p.trigger());
}
