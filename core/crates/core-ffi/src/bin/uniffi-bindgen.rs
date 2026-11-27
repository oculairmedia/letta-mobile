//! UniFFI's binding generator, pinned to this workspace's uniffi version so the generated Kotlin
//! always matches the scaffolding compiled into `baken_core`.

fn main() {
    uniffi::uniffi_bindgen_main();
}
