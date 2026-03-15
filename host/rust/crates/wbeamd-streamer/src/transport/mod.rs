mod sender;

use crate::cli::StreamMode;

/// HELLO byte[5] codec flag — signals HEVC/H.265 stream to the Android client.
pub(crate) const HELLO_CODEC_HEVC: u8 = 0x01;
pub(crate) const HELLO_CODEC_PNG: u8 = 0x02;
const HELLO_MODE_ULTRA: u8 = 0x10;
const HELLO_MODE_STABLE: u8 = 0x20;
const HELLO_MODE_QUALITY: u8 = 0x30;

pub(crate) fn hello_mode_bits(mode: StreamMode) -> u8 {
    match mode {
        StreamMode::Ultra => HELLO_MODE_ULTRA,
        StreamMode::Stable => HELLO_MODE_STABLE,
        StreamMode::Quality => HELLO_MODE_QUALITY,
    }
}

pub(crate) use sender::spawn_sender;
