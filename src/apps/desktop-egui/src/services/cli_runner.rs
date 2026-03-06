use crate::domain::cli::DevtoolCommand;
use std::path::PathBuf;
use std::process::Command;

pub(crate) fn run_cli_command(cmd: DevtoolCommand) -> Result<i32, String> {
    match cmd {
        DevtoolCommand::Build => run_build_all().map(|_| 0),
        DevtoolCommand::Deploy => run_deploy_all().map(|_| 0),
        DevtoolCommand::Help => {
            DevtoolCommand::print_help();
            Ok(0)
        }
        DevtoolCommand::Gui => Err("GUI command cannot be executed via CLI runner".to_string()),
    }
}

fn run_build_all() -> Result<(), String> {
    let root = repo_root();

    run_checked(
        "build host runtime",
        &mut Command::new("cargo")
            .arg("build")
            .arg("--release")
            .args(["-p", "wbeamd-server", "-p", "wbeamd-streamer"])
            .current_dir(root.join("src/host/rust")),
    )?;

    run_checked(
        "build desktop-tauri backend",
        &mut Command::new("cargo")
            .arg("build")
            .current_dir(root.join("src/apps/desktop-tauri/src-tauri")),
    )?;

    run_android_build("17")?;
    run_android_build("19")?;

    eprintln!("[devtool] build complete");
    Ok(())
}

fn run_android_build(min_sdk: &str) -> Result<(), String> {
    let root = repo_root();
    let android_dir = root.join("android");
    let gradlew = android_dir.join("gradlew");
    if !gradlew.exists() {
        return Err(format!("missing gradlew: {}", gradlew.display()));
    }

    run_checked(
        &format!("build android debug (minSdk={min_sdk})"),
        &mut Command::new("bash")
            .arg("./gradlew")
            .arg(format!("-PWBEAM_MIN_SDK={min_sdk}"))
            .args([
                "-PWBEAM_HOST=127.0.0.1",
                "-PWBEAM_API_HOST=127.0.0.1",
                "-PWBEAM_STREAM_HOST=127.0.0.1",
                ":app:assembleDebug",
            ])
            .current_dir(android_dir),
    )
}

fn run_deploy_all() -> Result<(), String> {
    let root = repo_root();
    let apk = root.join("android/app/build/outputs/apk/debug/app-debug.apk");
    if !apk.exists() {
        return Err(format!(
            "APK not found: {}. Run './devtool build' first.",
            apk.display()
        ));
    }

    let serials = adb_connected_serials()?;
    if serials.is_empty() {
        return Err("no connected adb devices in 'device' state".to_string());
    }

    for serial in &serials {
        eprintln!("[devtool][{serial}] install + reverse + launch");
        run_checked(
            &format!("adb install ({serial})"),
            &mut Command::new("adb")
                .arg("-s")
                .arg(serial)
                .args(["install", "-r"])
                .arg(&apk),
        )?;

        let _ = Command::new("adb")
            .arg("-s")
            .arg(serial)
            .args(["reverse", "tcp:5000", "tcp:5000"])
            .status();
        let _ = Command::new("adb")
            .arg("-s")
            .arg(serial)
            .args(["reverse", "tcp:5001", "tcp:5001"])
            .status();

        run_checked(
            &format!("adb launch ({serial})"),
            &mut Command::new("adb")
                .arg("-s")
                .arg(serial)
                .args([
                    "shell",
                    "am",
                    "start",
                    "-n",
                    "com.wbeam/.MainActivity",
                ]),
        )?;
    }

    eprintln!("[devtool] deploy complete for {} device(s)", serials.len());
    Ok(())
}

fn adb_connected_serials() -> Result<Vec<String>, String> {
    let output = Command::new("adb")
        .arg("devices")
        .output()
        .map_err(|err| format!("cannot run adb devices: {err}"))?;
    if !output.status.success() {
        return Err(format!("adb devices failed: {}", output.status));
    }
    let text = String::from_utf8_lossy(&output.stdout);
    let mut serials = Vec::new();
    for line in text.lines().skip(1) {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        let mut cols = trimmed.split_whitespace();
        let Some(serial) = cols.next() else {
            continue;
        };
        let Some(state) = cols.next() else {
            continue;
        };
        if state == "device" {
            serials.push(serial.to_string());
        }
    }
    Ok(serials)
}

fn run_checked(label: &str, cmd: &mut Command) -> Result<(), String> {
    eprintln!("[devtool] {label}");
    let status = cmd
        .status()
        .map_err(|err| format!("{label}: cannot start command: {err}"))?;
    if status.success() {
        return Ok(());
    }
    Err(format!("{label}: failed with status {status}"))
}

fn repo_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../")
}
