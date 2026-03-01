use std::collections::HashMap;

#[derive(Clone, Debug, Default)]
pub struct RuntimeProfile {
    pub name: String,
    pub values: HashMap<String, String>,
    pub quality: HashMap<String, String>,
    pub latency: HashMap<String, String>,
}
