# Letta Mobile Mermaid Native Renderer Spike

This crate is the Android JNI spike for replacing Mermaid WebView rendering with
an in-process Rust renderer.

## What it does

- accepts Mermaid DSL from Kotlin
- renders SVG via `mermaid-rs-renderer`
- returns SVG markup back through JNI
- lets the Android UI display that SVG without a WebView

## Current spike shape

- `MermaidNativeBridge` in `designsystem` tries to load `libletta_mermaid_renderer.so`
- when the native library is absent, the app silently falls back to the existing
  WebView path
- when the native library is present and returns SVG, `MermaidDiagram` renders it
  as an image instead of using WebView

## Build for Android

From this directory:

```bash
cargo install cargo-ndk
cargo ndk -t arm64-v8a -t armeabi-v7a -o ../../designsystem/src/main/jniLibs build --release
```

That should produce:

```text
android-compose/designsystem/src/main/jniLibs/
  arm64-v8a/libletta_mermaid_renderer.so
  armeabi-v7a/libletta_mermaid_renderer.so
```

Once those files exist, the Android app will prefer the native SVG path.

## Notes

- this spike intentionally uses manual JNI to keep the first pass small
- the Rust bridge currently ignores theme color arguments; it proves the render
  path first, then we can improve theming and parity later
