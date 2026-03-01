#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ApiLevelTier {
    Api17,
    Api18To28,
    Api29Plus,
}

impl ApiLevelTier {
    pub fn from_sdk(sdk: u32) -> Self {
        if sdk <= 17 {
            Self::Api17
        } else if sdk >= 29 {
            Self::Api29Plus
        } else {
            Self::Api18To28
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Self::Api17 => "api17",
            Self::Api18To28 => "api18_28",
            Self::Api29Plus => "api29_plus",
        }
    }
}
