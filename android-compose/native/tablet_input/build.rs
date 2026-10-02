fn main() {
    println!("cargo:rerun-if-changed=src/lib.rs");
    let tag = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|elapsed| elapsed.as_secs())
        .unwrap_or(0);
    println!("cargo:rustc-env=TABLET_BUILD_TAG={tag}");
}
