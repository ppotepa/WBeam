#[allow(dead_code)]
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum DiscoverySource {
    Adb,
    Lan,
    Wifi,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct DeviceInfo {
    pub(crate) discovery_source: DiscoverySource,
    pub(crate) source_identity: String,
    pub(crate) serial: String,
    pub(crate) adb_state: String,
    pub(crate) model: String,
    pub(crate) device_name: String,
    pub(crate) manufacturer: String,
    pub(crate) api_level: String,
    pub(crate) android_release: String,
    pub(crate) abi: String,
    pub(crate) characteristics: String,
    pub(crate) battery_level: String,
    pub(crate) battery_status: String,
}

impl DeviceInfo {
    pub(crate) fn sort_key(&self) -> &str {
        &self.serial
    }
}
