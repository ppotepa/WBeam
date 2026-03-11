//! WBTP/1 core – frame header definition, serialization, deserialization.
//!
//! Wire layout (all big-endian):
//!
//! ```text
//! Offset  Size  Field
//!  0       4    magic = b"WBTP"
//!  4       1    version (= 1)
//!  5       1    flags
//!  6       4    sequence (u32)
//! 10       8    capture_ts_us (u64, microseconds since UNIX epoch)
//! 18       4    payload_len (u32)
//! 22      [4]   crc32 (u32, present only when Flags::HAS_CHECKSUM is set)
//! ----+----
//! 22 or 26 bytes fixed header, then payload_len bytes of payload
//! ```

use bytes::{BufMut, Bytes, BytesMut};
use thiserror::Error;

// ── constants ────────────────────────────────────────────────────────────────

pub const MAGIC: &[u8; 4] = b"WBTP";
pub const VERSION: u8 = 1;

/// Size of the fixed part of the header (without optional CRC32).
pub const HEADER_BASE_LEN: usize = 22;
/// Size of the optional CRC32 field.
pub const CRC_LEN: usize = 4;
/// Maximum header size (with CRC32).
pub const HEADER_MAX_LEN: usize = HEADER_BASE_LEN + CRC_LEN;

// ── flags ────────────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Flags(pub u8);

impl Flags {
    /// CRC32 field is appended after the fixed header.
    pub const HAS_CHECKSUM: u8 = 0b0000_0001;
    /// This frame is an IDR / keyframe.
    pub const KEYFRAME: u8 = 0b0000_0010;
    /// Sender signals graceful end-of-stream.
    pub const END_OF_STREAM: u8 = 0b0000_0100;

    pub fn has_checksum(self) -> bool {
        self.0 & Self::HAS_CHECKSUM != 0
    }
    pub fn is_keyframe(self) -> bool {
        self.0 & Self::KEYFRAME != 0
    }
    pub fn is_eos(self) -> bool {
        self.0 & Self::END_OF_STREAM != 0
    }

    pub fn set_checksum(mut self) -> Self {
        self.0 |= Self::HAS_CHECKSUM;
        self
    }
    pub fn set_keyframe(mut self) -> Self {
        self.0 |= Self::KEYFRAME;
        self
    }
    pub fn set_eos(mut self) -> Self {
        self.0 |= Self::END_OF_STREAM;
        self
    }
}

// ── error ────────────────────────────────────────────────────────────────────

#[derive(Debug, Error)]
pub enum FrameError {
    #[error("bad magic: expected WBTP, got {0:?}")]
    BadMagic([u8; 4]),
    #[error("unsupported version: {0}")]
    UnsupportedVersion(u8),
    #[error("crc32 mismatch: expected {expected:#010x}, got {actual:#010x}")]
    CrcMismatch { expected: u32, actual: u32 },
    #[error("buffer too short: need {need}, have {have}")]
    TooShort { need: usize, have: usize },
    #[error("payload_len {0} exceeds MAX_PAYLOAD")]
    PayloadTooLarge(u32),
}

// ── header ───────────────────────────────────────────────────────────────────

/// Maximum allowed payload size (16 MiB). Guards against malformed packets.
pub const MAX_PAYLOAD: u32 = 16 * 1024 * 1024;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FrameHeader {
    pub flags: Flags,
    pub sequence: u32,
    /// Capture timestamp in microseconds since UNIX epoch.
    pub capture_ts_us: u64,
    pub payload_len: u32,
}

impl FrameHeader {
    /// Total header wire size including optional CRC32.
    pub fn wire_len(&self) -> usize {
        if self.flags.has_checksum() {
            HEADER_MAX_LEN
        } else {
            HEADER_BASE_LEN
        }
    }

    /// Serialize into `dst`, optionally appending CRC32 over the payload.
    ///
    /// `payload` is only required when `Flags::HAS_CHECKSUM` is set.
    pub fn encode(&self, dst: &mut BytesMut, payload: Option<&[u8]>) {
        dst.put_slice(MAGIC);
        dst.put_u8(VERSION);
        dst.put_u8(self.flags.0);
        dst.put_u32(self.sequence);
        dst.put_u64(self.capture_ts_us);
        dst.put_u32(self.payload_len);

        if self.flags.has_checksum() {
            let crc = crc32fast::hash(payload.unwrap_or(&[]));
            dst.put_u32(crc);
        }
    }

    /// Encode header + payload into a single contiguous [`Bytes`] buffer.
    pub fn encode_frame(&self, payload: &[u8]) -> Bytes {
        let mut buf = BytesMut::with_capacity(self.wire_len() + payload.len());
        self.encode(&mut buf, Some(payload));
        buf.put_slice(payload);
        buf.freeze()
    }

    /// Try to decode a header from `src`.
    ///
    /// Returns `(header, bytes_consumed)` on success.
    /// Returns `Err(FrameError::TooShort)` if more data is needed without
    /// consuming anything – the caller should accumulate bytes and retry.
    pub fn decode(src: &[u8]) -> Result<(Self, usize), FrameError> {
        // We need at least the fixed base first.
        if src.len() < HEADER_BASE_LEN {
            return Err(FrameError::TooShort {
                need: HEADER_BASE_LEN,
                have: src.len(),
            });
        }

        let magic: [u8; 4] = src[0..4].try_into().unwrap();
        if &magic != MAGIC {
            return Err(FrameError::BadMagic(magic));
        }

        let version = src[4];
        if version != VERSION {
            return Err(FrameError::UnsupportedVersion(version));
        }

        let flags = Flags(src[5]);
        let sequence = u32::from_be_bytes(src[6..10].try_into().unwrap());
        let capture_ts_us = u64::from_be_bytes(src[10..18].try_into().unwrap());
        let payload_len = u32::from_be_bytes(src[18..22].try_into().unwrap());

        if payload_len > MAX_PAYLOAD {
            return Err(FrameError::PayloadTooLarge(payload_len));
        }

        let header_len = if flags.has_checksum() {
            HEADER_MAX_LEN
        } else {
            HEADER_BASE_LEN
        };
        if src.len() < header_len {
            return Err(FrameError::TooShort {
                need: header_len,
                have: src.len(),
            });
        }

        let header = FrameHeader {
            flags,
            sequence,
            capture_ts_us,
            payload_len,
        };
        Ok((header, header_len))
    }

    /// Validate CRC32 of `payload` against the stored checksum.
    ///
    /// `header_bytes` must be the raw bytes of the fixed header (22 bytes) +
    /// crc32 field (4 bytes) so we can extract the expected value.
    pub fn verify_crc(expected_crc: u32, payload: &[u8]) -> Result<(), FrameError> {
        let actual = crc32fast::hash(payload);
        if actual != expected_crc {
            return Err(FrameError::CrcMismatch {
                expected: expected_crc,
                actual,
            });
        }
        Ok(())
    }

    /// Extract the stored CRC32 from the wire bytes (must include the CRC field).
    pub fn extract_crc(header_bytes: &[u8]) -> u32 {
        u32::from_be_bytes(header_bytes[22..26].try_into().unwrap())
    }
}

// ── tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    fn make_header(seq: u32, keyframe: bool, checksum: bool) -> FrameHeader {
        let mut flags = Flags::default();
        if keyframe {
            flags = flags.set_keyframe();
        }
        if checksum {
            flags = flags.set_checksum();
        }
        FrameHeader {
            flags,
            sequence: seq,
            capture_ts_us: 1_700_000_000_000_000,
            payload_len: 4,
        }
    }

    #[test]
    fn roundtrip_no_crc() {
        let payload = b"TEST";
        let h = make_header(42, false, false);
        let frame = h.encode_frame(payload);
        assert_eq!(frame.len(), HEADER_BASE_LEN + 4);

        let (decoded, consumed) = FrameHeader::decode(&frame).unwrap();
        assert_eq!(consumed, HEADER_BASE_LEN);
        assert_eq!(decoded.sequence, 42);
        assert_eq!(decoded.payload_len, 4);
        assert!(!decoded.flags.has_checksum());
    }

    #[test]
    fn roundtrip_with_crc() {
        let payload = b"TEST";
        let h = make_header(7, true, true);
        let frame = h.encode_frame(payload);
        assert_eq!(frame.len(), HEADER_MAX_LEN + 4);

        let (decoded, consumed) = FrameHeader::decode(&frame).unwrap();
        assert_eq!(consumed, HEADER_MAX_LEN);
        assert!(decoded.flags.has_checksum());
        assert!(decoded.flags.is_keyframe());

        let crc = FrameHeader::extract_crc(&frame[..HEADER_MAX_LEN]);
        FrameHeader::verify_crc(crc, payload).unwrap();
    }

    #[test]
    fn bad_magic_rejected() {
        let mut data = vec![0u8; HEADER_BASE_LEN];
        data[0..4].copy_from_slice(b"NOPE");
        assert!(matches!(
            FrameHeader::decode(&data),
            Err(FrameError::BadMagic(_))
        ));
    }

    #[test]
    fn too_short_returns_need() {
        let data = [0u8; 10];
        assert!(matches!(
            FrameHeader::decode(&data),
            Err(FrameError::TooShort { need: 22, .. })
        ));
    }
}
