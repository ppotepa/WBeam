use std::process::Command;

fn main() {
    println!("cargo:rerun-if-env-changed=WBEAM_BUILD_REV");
    println!("cargo:rerun-if-changed=../../.git/HEAD");

    let revision_suffix = std::env::var("WBEAM_BUILD_REV")
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| git_short_rev().unwrap_or_else(|| "dev0".to_string()));

    let normalized = if revision_suffix.starts_with("0.0.") {
        revision_suffix
    } else {
        format!("0.0.{revision_suffix}-build")
    };

    println!("cargo:rustc-env=WBEAM_BUILD_REV={normalized}");
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
