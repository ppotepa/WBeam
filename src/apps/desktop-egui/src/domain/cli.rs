#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum DevtoolCommand {
    Gui,
    Build,
    Deploy,
    Help,
}

impl DevtoolCommand {
    pub(crate) fn parse(args: &[String]) -> Result<Self, String> {
        if args.is_empty() {
            return Ok(Self::Gui);
        }

        let first = args[0].as_str();
        match first {
            "gui" => Ok(Self::Gui),
            "help" | "-h" | "--help" => Ok(Self::Help),
            "build" => Ok(Self::Build),
            "deploy" => Ok(Self::Deploy),
            "project" => {
                if args.len() < 2 {
                    return Err("missing project command (expected: build|deploy)".to_string());
                }
                match args[1].as_str() {
                    "build" => Ok(Self::Build),
                    "deploy" => Ok(Self::Deploy),
                    other => Err(format!("unsupported project command: {other}")),
                }
            }
            other => Err(format!("unsupported command: {other}")),
        }
    }

    pub(crate) fn print_help() {
        eprintln!(
            "WBeam Devtool (Rust + egui)\n\nUsage:\n  ./devtool                 # desktop GUI\n  ./devtool gui             # desktop GUI\n  ./devtool build           # build host + desktop-tauri + android (legacy + modern)\n  ./devtool deploy          # install + launch APK on all connected ADB devices\n  ./devtool project build\n  ./devtool project deploy\n"
        );
    }
}
