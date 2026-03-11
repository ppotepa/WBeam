use crate::domain::profile::RuntimeProfile;

pub trait ProfileRepositoryPort: Send + Sync {
    fn list(&self) -> Result<Vec<String>, String>;
    fn get(&self, name: &str) -> Result<RuntimeProfile, String>;
}
