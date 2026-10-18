# Native Mermaid renderer

Rust renderer used through JNI by Android's `MermaidNativeBridge` and
`MermaidDiagram` in `designsystem`. It renders Mermaid DSL to SVG in process;
`src/lib.rs` applies Material theme colors and contrast adjustments. It no longer
ignores the theme arguments.

When the native library cannot load or rendering fails, the Android component
shows `MermaidErrorFallback` with the source/error. There is no WebView fallback
in the current component. Successful SVGs are displayed with Coil's SVG decoder.

From this directory, a manual Android build is:

```bash
cargo install cargo-ndk
cargo ndk -t arm64-v8a -t armeabi-v7a -o ../../designsystem/src/main/jniLibs build --release
```

This emits `libletta_mermaid_renderer.so` per ABI under the design-system
`src/main/jniLibs` directory. The checked-in Kotlin loader uses the library name
`letta_mermaid_renderer`; Android NDK, Rust targets and a compatible toolchain
must be installed. This documentation audit inspected the Rust and Kotlin code;
it did not rebuild native binaries or verify rendering on a device.
