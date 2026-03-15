fn main() {
    // Link against libevdi for the EVDI direct-capture backend (Linux only).
    // libevdi.so must be installed (e.g. via evdi-dkms package).
    #[cfg(target_os = "linux")]
    println!("cargo:rustc-link-lib=evdi");
}
