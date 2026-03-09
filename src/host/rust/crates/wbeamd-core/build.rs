use std::process::Command;

fn main() {
    println!("cargo:rerun-if-env-changed=WBEAM_BUILD_REV");
    println!("cargo:rerun-if-changed=../../.git/HEAD");

    let revision = std::env::var("WBEAM_BUILD_REV")
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| {
            format!(
                "0.1.0.0.{}",
                git_short_rev().unwrap_or_else(|| "dev0".to_string())
            )
        });

    // Keep revision as a direct pass-through. Any normalization here breaks
    // host/APK version parity and causes false mismatch diagnostics.
    println!("cargo:rustc-env=WBEAM_BUILD_REV={revision}");
}

fn git_short_rev() -> Option<String> {
    let repo_hint = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..");
    let output = Command::new("git")
        .arg("-C")
        .arg(repo_hint)
        .args(["rev-parse", "--short=4", "HEAD"])
        .output()
        .ok()?;

    if !output.status.success() {
        return None;
    }

    let value = String::from_utf8(output.stdout).ok()?.trim().to_string();
    if value.is_empty() {
        None
    } else {
        Some(value)
    }
}
