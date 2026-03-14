//! Persistent runtime-configuration store.
//!
//! Loads and saves `ActiveConfig` as pretty-printed JSON to a file in the
//! config directory, so that the daemon survives restarts with the last-used
//! settings.

use std::collections::BTreeMap;
use std::fs;
use std::path::Path;

use wbeamd_api::{validate_config, validate_config_with_presets, ActiveConfig, ConfigPatch};

fn default_config_from_presets(presets: &BTreeMap<String, ActiveConfig>) -> ActiveConfig {
    for key in ["default", "baseline"] {
        if let Some(cfg) = presets.get(key) {
            return cfg.clone();
        }
    }
    if let Some((_name, cfg)) = presets.iter().next() {
        return cfg.clone();
    }
    ActiveConfig::balanced_default()
}

/// Read and validate `ActiveConfig` from `path`.  Returns `None` if the file
/// is missing, unreadable, or fails validation.
pub fn load_runtime_config(path: &Path) -> Option<ActiveConfig> {
    let raw = fs::read_to_string(path).ok()?;
    let parsed: ActiveConfig = serde_json::from_str(&raw).ok()?;

    let patch = ConfigPatch {
        profile: Some(parsed.profile),
        encoder: Some(parsed.encoder),
        cursor_mode: Some(parsed.cursor_mode),
        size: Some(parsed.size),
        fps: Some(parsed.fps),
        bitrate_kbps: Some(parsed.bitrate_kbps),
        debug_fps: Some(parsed.debug_fps),
        intra_only: Some(parsed.intra_only),
    };

    validate_config(patch, &ActiveConfig::balanced_default()).ok()
}

pub fn load_runtime_config_with_presets(
    path: &Path,
    presets: &BTreeMap<String, ActiveConfig>,
) -> Option<ActiveConfig> {
    let raw = fs::read_to_string(path).ok()?;
    let parsed: ActiveConfig = serde_json::from_str(&raw).ok()?;

    let fallback = default_config_from_presets(presets);

    let patch = ConfigPatch {
        profile: Some(parsed.profile),
        encoder: Some(parsed.encoder),
        cursor_mode: Some(parsed.cursor_mode),
        size: Some(parsed.size),
        fps: Some(parsed.fps),
        bitrate_kbps: Some(parsed.bitrate_kbps),
        debug_fps: Some(parsed.debug_fps),
        intra_only: Some(parsed.intra_only),
    };

    validate_config_with_presets(patch, &fallback, presets).ok()
}

/// Serialize `config` to `path`, creating parent directories as needed.
pub fn persist_config(path: &Path, config: &ActiveConfig) -> Result<(), String> {
    let parent = path
        .parent()
        .ok_or_else(|| "invalid runtime config path".to_string())?;

    fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    let serialized = serde_json::to_string_pretty(config).map_err(|e| e.to_string())?;
    fs::write(path, serialized).map_err(|e| e.to_string())?;
    Ok(())
}
