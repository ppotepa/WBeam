use serde::{Deserialize, Serialize};
use std::fs;
use std::path::PathBuf;

pub(crate) const TRAINED_PROFILES_VERSION: u32 = 1;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub(crate) struct StoredTrainedProfile {
    pub(crate) key: String,
    pub(crate) name: String,
    #[serde(default = "default_profile_backend")]
    pub(crate) backend: String,
    pub(crate) codec: String,
    pub(crate) objective: String,
    pub(crate) workload: String,
    pub(crate) encoder: String,
    pub(crate) bitrate_kbps: u32,
    pub(crate) fps: u32,
    pub(crate) intra_only: bool,
    pub(crate) created_unix_ms: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub(crate) struct StoredTrainedProfiles {
    #[serde(default = "trained_profiles_version")]
    pub(crate) version: u32,
    #[serde(default)]
    pub(crate) profiles: Vec<StoredTrainedProfile>,
}

pub(crate) fn trained_profiles_version() -> u32 {
    TRAINED_PROFILES_VERSION
}

pub(crate) fn default_profile_backend() -> String {
    "wayland_portal".to_string()
}

fn normalize_backend(raw: &str) -> String {
    raw.trim().to_ascii_lowercase()
}

fn curated_profiles_for_backend(backend: &str) -> Option<Vec<StoredTrainedProfile>> {
    let backend = normalize_backend(backend);
    if backend != "evdi" && backend != "wayland_portal" {
        return None;
    }

    let mut profiles = Vec::new();
    let h264_bitrates_kbps = [1_000u32, 5_000, 10_000, 20_000, 35_000, 50_000, 75_000, 100_000, 150_000, 200_000];
    let h265_bitrates_kbps = [1_000u32, 5_000, 10_000, 20_000, 35_000, 50_000, 75_000, 100_000];
    let mut created_unix_ms = 1_800_000_000_000u64;

    for (encoder, bitrates_kbps) in [("h264", h264_bitrates_kbps.as_slice()), ("h265", h265_bitrates_kbps.as_slice())] {
        for bitrate_kbps in bitrates_kbps {
            let mbps = bitrate_kbps / 1_000;
            profiles.push(StoredTrainedProfile {
                key: format!("{backend}-{encoder}-{mbps}mbps-60fps"),
                name: format!("{backend} {encoder} {mbps}mbps 60fps"),
                backend: backend.clone(),
                codec: encoder.to_string(),
                objective: "manual-quality".to_string(),
                workload: "connect".to_string(),
                encoder: encoder.to_string(),
                bitrate_kbps: *bitrate_kbps,
                fps: 60,
                intra_only: false,
                created_unix_ms,
            });
            created_unix_ms = created_unix_ms.saturating_add(1);
        }
    }

    Some(profiles)
}

impl Default for StoredTrainedProfiles {
    fn default() -> Self {
        Self {
            version: TRAINED_PROFILES_VERSION,
            profiles: Vec::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct TrainedProfile {
    key: String,
    name: String,
    backend: String,
    codec: String,
    objective: String,
    workload: String,
    encoder: String,
    bitrate_kbps: u32,
    fps: u32,
    intra_only: bool,
    created_unix_ms: u64,
}

impl From<StoredTrainedProfile> for TrainedProfile {
    fn from(value: StoredTrainedProfile) -> Self {
        Self {
            key: value.key,
            name: value.name,
            backend: value.backend,
            codec: value.codec,
            objective: value.objective,
            workload: value.workload,
            encoder: value.encoder,
            bitrate_kbps: value.bitrate_kbps,
            fps: value.fps,
            intra_only: value.intra_only,
            created_unix_ms: value.created_unix_ms,
        }
    }
}

pub(crate) fn trained_profiles_path(backend: &str) -> Result<PathBuf, String> {
    let dir = crate::wbeam_user_config_dir()
        .ok_or_else(|| "cannot resolve user config directory".to_string())?
        .join("profiles")
        .join(normalize_backend(backend));
    fs::create_dir_all(&dir).map_err(|e| format!("failed to create {}: {e}", dir.display()))?;
    Ok(dir.join("trained_profiles.json"))
}

pub(crate) fn migrate_legacy_profiles_once() {
    let Some(base) = crate::wbeam_user_config_dir() else { return };
    let legacy = base.join("trained_profiles.json");
    let archive = base.join("trained_profiles.legacy.json");
    if legacy.exists() && !archive.exists() {
        let _ = fs::rename(&legacy, &archive);
    }
}

pub(crate) fn load_trained_profiles_store(backend: &str) -> Result<StoredTrainedProfiles, String> {
    let path = trained_profiles_path(backend)?;
    if !path.exists() {
        return Ok(StoredTrainedProfiles::default());
    }
    let raw = fs::read_to_string(&path)
        .map_err(|e| format!("failed to read {}: {e}", path.display()))?;
    serde_json::from_str::<StoredTrainedProfiles>(&raw)
        .map_err(|e| format!("failed to parse {}: {e}", path.display()))
}

pub(crate) fn find_trained_profile(
    selector: &str,
    backend: &str,
) -> Result<Option<StoredTrainedProfile>, String> {
    let needle = selector.trim();
    if needle.is_empty() {
        return Ok(None);
    }
    if let Some(curated) = curated_profiles_for_backend(backend) {
        return Ok(curated.into_iter().find(|profile| {
            profile.key.eq_ignore_ascii_case(needle) || profile.name.eq_ignore_ascii_case(needle)
        }));
    }
    let store = load_trained_profiles_store(backend)?;
    Ok(store.profiles.into_iter().find(|profile| {
        profile.key.eq_ignore_ascii_case(needle) || profile.name.eq_ignore_ascii_case(needle)
    }))
}

#[tauri::command]
pub(crate) fn list_trained_profiles(backend: String) -> Result<Vec<TrainedProfile>, String> {
    if let Some(curated) = curated_profiles_for_backend(&backend) {
        return Ok(curated.into_iter().map(TrainedProfile::from).collect());
    }
    let mut profiles = load_trained_profiles_store(&backend)?.profiles;
    profiles.sort_by(|a, b| b.created_unix_ms.cmp(&a.created_unix_ms));
    Ok(profiles.into_iter().map(TrainedProfile::from).collect())
}
