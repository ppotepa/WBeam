fn main() {
    // Only link libevdi when the optional `evdi` feature is enabled.
    if cfg!(target_os = "linux") && std::env::var_os("CARGO_FEATURE_EVDI").is_some() {
        println!("cargo:rustc-link-lib=evdi");
    }
}
