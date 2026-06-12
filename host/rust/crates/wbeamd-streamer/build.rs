fn main() {
    // Only link libevdi when the optional `evdi` feature is enabled.
    if cfg!(target_os = "linux") && std::env::var_os("CARGO_FEATURE_EVDI").is_some() {
        println!("cargo:rerun-if-env-changed=WBEAM_EVDI_LIB_DIR");
        if let Some(lib_dir) = evdi_lib_dir() {
            println!("cargo:rustc-link-search=native={}", lib_dir.display());
            println!("cargo:rustc-link-arg=-Wl,-rpath,{}", lib_dir.display());
        }
        println!("cargo:rustc-link-lib=evdi");
    }
}

fn evdi_lib_dir() -> Option<std::path::PathBuf> {
    if let Some(path) = std::env::var_os("WBEAM_EVDI_LIB_DIR") {
        let path = std::path::PathBuf::from(path);
        if path.join("libevdi.so").exists() {
            return Some(path);
        }
    }

    [
        "/usr/libexec/displaylink",
        "/usr/lib64",
        "/usr/lib",
        "/usr/local/lib64",
        "/usr/local/lib",
    ]
    .into_iter()
    .map(std::path::PathBuf::from)
    .find(|path| path.join("libevdi.so").exists())
}
