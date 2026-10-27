#!/usr/bin/env python3
"""Render the actual mascot through the packaged C ABI. No display server or mock renderer.

Fails explicitly when EGL/GL is unavailable. Software GL results are labelled separately from
hardware rendering; --require-hardware rejects them. Evidence uses PNG and raw premultiplied RGBA.
"""
import argparse
import ctypes as c
import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path
import struct
import zlib


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def write_png(path, pixels, width, height):
    # PNG stores straight alpha; retain the original premultiplied bytes alongside it.
    straight = bytearray(pixels)
    for i in range(0, len(straight), 4):
        alpha = straight[i + 3]
        if alpha:
            for channel in range(3):
                straight[i + channel] = min(255, (straight[i + channel] * 255 + alpha // 2) // alpha)
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    scanlines = b''.join(b'\0' + straight[y * width * 4:(y + 1) * width * 4] for y in range(height))
    path.write_bytes(b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>2I5B', width, height, 8, 6, 0, 0, 0)) +
                     chunk(b'IDAT', zlib.compress(scanlines)) + chunk(b'IEND', b''))


# Test-only LD_PRELOAD shim: sabotage the first context after its pbuffer exists. This
# exercises the real driver and production bridge without adding production test switches.
FALLBACK_SHIM = r"""
#include <EGL/egl.h>
#include <dlfcn.h>
#include <cstdio>
#include <cstdlib>
static EGLDisplay rejected = EGL_NO_DISPLAY;
static EGLContext rejectedContext = EGL_NO_CONTEXT;
static EGLSurface rejectedSurface = EGL_NO_SURFACE;
static bool detached, surfaceDestroyed, contextDestroyed, terminated, released, advanced;
#define REAL(name) auto real = reinterpret_cast<decltype(&name)>(dlsym(RTLD_NEXT, #name))
extern "C" EGLBoolean eglMakeCurrent(EGLDisplay d, EGLSurface draw, EGLSurface read, EGLContext ctx) {
    REAL(eglMakeCurrent);
    if (rejected == EGL_NO_DISPLAY && ctx != EGL_NO_CONTEXT) {
        rejected = d; rejectedContext = ctx; rejectedSurface = draw;
        std::fprintf(stderr, "EGL FALLBACK TEST: rejected first makeCurrent after context+pbuffer\n");
        return EGL_FALSE;
    }
    auto ok = real(d, draw, read, ctx);
    if (d == rejected && ctx == EGL_NO_CONTEXT && ok) detached = true;
    return ok;
}
extern "C" EGLBoolean eglDestroySurface(EGLDisplay d, EGLSurface s) {
    REAL(eglDestroySurface); auto ok = real(d, s);
    if (d == rejected && s == rejectedSurface && ok) surfaceDestroyed = true;
    return ok;
}
extern "C" EGLBoolean eglDestroyContext(EGLDisplay d, EGLContext ctx) {
    REAL(eglDestroyContext); auto ok = real(d, ctx);
    if (d == rejected && ctx == rejectedContext && ok) contextDestroyed = true;
    return ok;
}
extern "C" EGLBoolean eglTerminate(EGLDisplay d) {
    REAL(eglTerminate); auto ok = real(d);
    if (d == rejected && ok) terminated = true;
    return ok;
}
extern "C" EGLBoolean eglReleaseThread() {
    REAL(eglReleaseThread); auto ok = real();
    if (rejected != EGL_NO_DISPLAY && ok) released = true;
    return ok;
}
extern "C" EGLBoolean eglInitialize(EGLDisplay d, EGLint* major, EGLint* minor) {
    if (rejected != EGL_NO_DISPLAY && !advanced) {
        if (!(detached && surfaceDestroyed && contextDestroyed && terminated && released)) {
            std::fprintf(stderr, "EGL FALLBACK TEST: incomplete cleanup before next candidate\n");
            std::abort();
        }
        advanced = true;
        std::fprintf(stderr, "EGL FALLBACK TEST: next candidate after full cleanup\n");
    }
    REAL(eglInitialize); return real(d, major, minor);
}
"""


def test_fallback(library, asset, output, require_hardware):
    output.mkdir(parents=True, exist_ok=True)
    source, shim = output / 'fail-first-egl.cpp', output / 'fail-first-egl.so'
    source.write_text(FALLBACK_SHIM)
    subprocess.run(['clang++', '-shared', '-fPIC', '-std=c++17', str(source), '-o', str(shim),
                    '-Wl,--no-as-needed', '-lEGL', '-ldl'], check=True)
    env = dict(os.environ, LD_PRELOAD=str(shim.resolve()))
    command = [sys.executable, str(Path(__file__).resolve()), str(library.resolve()), str(asset.resolve()),
               str(output.resolve())]
    if require_hardware:
        command.append('--require-hardware')
    result = subprocess.run(command, env=env, capture_output=True, text=True)
    log = result.stdout + result.stderr
    (output / 'fallback-test.log').write_text(log)
    print(log, end='', flush=True)
    require(result.returncode == 0, f'Fallback smoke failed ({result.returncode})')
    require('EGL FALLBACK TEST: rejected first makeCurrent after context+pbuffer' in log, 'Failure was not injected')
    require('EGL FALLBACK TEST: next candidate after full cleanup' in log, 'No fully cleaned-up fallback attempted')
    print('FALLBACK PASS: first candidate rejected; next candidate rendered actual mascot', flush=True)


class SmokeScenes:
    """Own the C ABI bindings, live scenes and rendered frame evidence for one smoke run."""

    def __init__(self, library, asset, output):
        self.native = c.CDLL(str(library.resolve()))
        signatures = {
            'create': (c.c_void_p, [c.c_char_p, c.c_int]),
            'destroy': (None, [c.c_void_p]),
            'load': (c.c_int, [c.c_void_p, c.c_char_p, c.c_int, c.c_char_p]),
            'advance': (None, [c.c_void_p, c.c_float]),
            'render': (c.c_int, [c.c_void_p, c.c_int, c.c_int, c.c_uint32, c.c_void_p]),
            'vm_set_enum': (c.c_int, [c.c_void_p, c.c_char_p, c.c_char_p]),
            'vm_set_number': (c.c_int, [c.c_void_p, c.c_char_p, c.c_float]),
            'vm_set_boolean': (c.c_int, [c.c_void_p, c.c_char_p, c.c_int]),
            'vm_set_color': (c.c_int, [c.c_void_p, c.c_char_p, c.c_uint32]),
            'vm_fire': (c.c_int, [c.c_void_p, c.c_char_p]),
        }
        for name, (result, args) in signatures.items():
            function = getattr(self.native, 'rive_bridge_' + name)
            function.restype, function.argtypes = result, args
        self.data = asset.read_bytes()
        self.output = output
        self.scenes = []
        self.frames = {}
        self.adapter = c.create_string_buffer(2048)

    def create(self):
        scene = self.native.rive_bridge_create(self.adapter, len(self.adapter))
        require(scene, 'GPU SETUP UNAVAILABLE: ' + self.adapter.value.decode())
        self.scenes.append(scene)
        require(self.native.rive_bridge_load(scene, self.data, len(self.data), b'Avatar') == 0, 'Shipped mascot failed to load')
        require(self.native.rive_bridge_vm_set_enum(scene, b'shape', b'roundedSquare') == 0, 'Missing identity shape')
        require(self.native.rive_bridge_vm_set_color(scene, b'color', 0xff7189e6) == 0, 'Missing identity color')
        require(self.native.rive_bridge_vm_set_enum(scene, b'state', b'idle') == 0, 'Missing state enum')
        return scene

    def frame(self, scene, label, size=(192, 192), advance=1):
        width, height = size
        for _ in range(advance):
            self.native.rive_bridge_advance(scene, 1 / 60)
        out = (c.c_ubyte * (width * height * 4))()
        require(self.native.rive_bridge_render(scene, width, height, 0, out) == 0, f'Render failed: {label}')
        pixels = bytes(out)
        self.frames[label] = self.validate_pixels(pixels, width, height, label)
        (self.output / (label + '.rgba')).write_bytes(pixels)
        write_png(self.output / (label + '.png'), pixels, width, height)
        return pixels

    @staticmethod
    def validate_pixels(pixels, width, height, label):
        alpha = pixels[3::4]
        require(sum(a > 0 for a in alpha) > width * height // 100, f'Empty frame: {label}')
        require(0 in alpha and any(0 < a < 255 for a in alpha), f'Transparency/soft edges missing: {label}')
        require(all(max(pixels[i:i + 3]) <= pixels[i + 3] + 1 for i in range(0, len(pixels), 4)),
                f'Pixels are not premultiplied: {label}')
        return dict(width=width, height=height, sha256=hashlib.sha256(pixels).hexdigest(),
                    visible=sum(a > 0 for a in alpha), partial_alpha=sum(0 < a < 255 for a in alpha))

    def destroy(self, scene):
        self.native.rive_bridge_destroy(scene)
        self.scenes.remove(scene)

    def close(self):
        for scene in reversed(self.scenes):
            self.native.rive_bridge_destroy(scene)


def smoke(library, asset, output, require_hardware=False):
    output.mkdir(parents=True, exist_ok=True)
    (output / 'render-evidence.json').unlink(missing_ok=True)
    scenes = SmokeScenes(library, asset, output)
    try:
        first, second = scenes.create(), scenes.create()
        renderer = scenes.adapter.value.decode()
        software = any(name in renderer.lower() for name in ('llvmpipe', 'softpipe', 'swrast', 'software'))
        print(('SOFTWARE GL' if software else 'HARDWARE GL') + ': ' + renderer, flush=True)
        require(not (require_hardware and software), 'Hardware rendering required; software GL is not a hardware pass')
        baseline = scenes.frame(first, 'idle', advance=30)
        require(scenes.native.rive_bridge_vm_set_enum(first, b'state', b'thinking') == 0, 'Missing state enum')
        thinking = scenes.frame(first, 'thinking', advance=20)
        require(thinking != baseline, 'State input did not change pixels')
        require(scenes.frame(first, 'thinking-next', advance=8) != thinking, 'Animation did not advance')
        require(scenes.native.rive_bridge_vm_set_enum(second, b'shape', b'cloud') == 0, 'Missing cloud identity')
        for name, value in ((b'lookX', 0.8), (b'mouthOpen', 0.9), (b'turnX', 0.5)):
            require(scenes.native.rive_bridge_vm_set_number(second, name, value) == 0, 'Missing number input')
        require(scenes.native.rive_bridge_vm_set_boolean(second, b'hovered', 1) == 0, 'Missing boolean input')
        require(scenes.native.rive_bridge_vm_set_color(second, b'color', 0xff3ba4b0) == 0, 'Missing identity color')
        require(scenes.native.rive_bridge_vm_fire(second, b'success') == 0, 'Missing reaction trigger')
        require(scenes.frame(second, 'inputs', advance=12) != baseline, 'Independent scene inputs did not change pixels')
        scratch = (c.c_ubyte * 4)()
        require(scenes.native.rive_bridge_render(first, 0, 1, 0, scratch) != 0, 'Zero viewport accepted')
        require(scenes.native.rive_bridge_render(first, 2**31 - 1, 1, 0, scratch) != 0, 'Oversized viewport accepted')
        scenes.frame(first, 'resized', (128, 240), advance=1)
        scenes.frame(second, 'other-scene', (96, 96), advance=1)
        scenes.destroy(first)
        scenes.frame(second, 'survivor', advance=2)
        scenes.destroy(second)
        # Last-scene destruction releases files, targets, Rive and EGL; recreate to catch stale globals.
        for _ in range(3):
            scene = scenes.create()
            scenes.frame(scene, 'recreated', (64, 64))
            scenes.destroy(scene)
        evidence = dict(renderer=renderer, hardware=not software, asset_sha256=hashlib.sha256(scenes.data).hexdigest(),
                        frames=scenes.frames, result='PASS')
        (output / 'render-evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
        print(json.dumps(evidence, sort_keys=True), flush=True)
        return evidence
    finally:
        scenes.close()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('library', type=Path)
    parser.add_argument('asset', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--require-hardware', action='store_true')
    parser.add_argument('--test-fallback', action='store_true',
                        help='Compile a test-only EGL shim, fail the first makeCurrent, require cleanup and rendered fallback')
    args = parser.parse_args()
    if args.test_fallback:
        test_fallback(args.library, args.asset, args.output, args.require_hardware)
    else:
        smoke(args.library, args.asset, args.output, args.require_hardware)
