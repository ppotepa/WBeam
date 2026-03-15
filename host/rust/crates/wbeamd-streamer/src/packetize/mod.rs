use std::io::{IoSlice, Write};
use std::net::TcpStream;

use wbtp_core::{Flags, MAGIC, VERSION};

const HELLO_MAGIC: &[u8; 4] = b"WBS1";
/// WBTP Hello v2: extends the original 16-byte Hello with width, height and fps
/// to give the Android client authoritative stream geometry.
const HELLO_VERSION: u8 = 0x02;
const HELLO_SIZE_V1: u16 = 16;
const HELLO_SIZE_V2: u16 = 24;

/// Build a 22-byte WBTP frame header directly into a stack array.
#[inline]
pub(crate) fn build_header(seq: u32, pts_us: u64, payload_len: usize, is_key: bool) -> [u8; 22] {
    let mut h = [0u8; 22];
    h[0..4].copy_from_slice(MAGIC);
    h[4] = VERSION;
    h[5] = if is_key { Flags::KEYFRAME } else { 0 };
    h[6..10].copy_from_slice(&seq.to_be_bytes());
    h[10..18].copy_from_slice(&pts_us.to_be_bytes());
    h[18..22].copy_from_slice(&(payload_len as u32).to_be_bytes());
    h
}

/// Build a 24-byte WBTP HELLO v2 greeting for a new connection.
///
/// Layout:
///   [0-3]   magic "WBS1"
///   [4]     version = 0x02
///   [5]     codec/mode flags (same as v1)
///   [6-7]   helloLen = 24  (u16 big-endian)
///   [8-15]  session_id     (u64 big-endian)
///   [16-17] width          (u16 big-endian)
///   [18-19] height         (u16 big-endian)
///   [20-21] fps            (u16 big-endian)
///   [22-23] reserved       (0x00, 0x00)
///
/// Android clients that still expect v1 (helloLen == 16) will reject this;
/// update the Android HELLO_HEADER_SIZE and version check accordingly.
#[inline]
pub(crate) fn build_hello(
    session_id: u64,
    codec_flags: u8,
    width: u32,
    height: u32,
    fps: u32,
) -> [u8; 24] {
    let mut buf = [0u8; 24];
    buf[0..4].copy_from_slice(HELLO_MAGIC);
    buf[4] = HELLO_VERSION;
    buf[5] = codec_flags;
    buf[6..8].copy_from_slice(&HELLO_SIZE_V2.to_be_bytes());
    buf[8..16].copy_from_slice(&session_id.to_be_bytes());
    buf[16..18].copy_from_slice(&(width.min(u16::MAX as u32) as u16).to_be_bytes());
    buf[18..20].copy_from_slice(&(height.min(u16::MAX as u32) as u16).to_be_bytes());
    buf[20..22].copy_from_slice(&(fps.min(u16::MAX as u32) as u16).to_be_bytes());
    // buf[22..24] stays 0x00 (reserved)
    buf
}

/// Build a legacy 16-byte WBTP HELLO v1 greeting (for testing / fallback).
#[allow(dead_code)]
#[inline]
pub(crate) fn build_hello_v1(session_id: u64, codec_flags: u8) -> [u8; 16] {
    let mut buf = [0u8; 16];
    buf[0..4].copy_from_slice(HELLO_MAGIC);
    buf[4] = 0x01; // v1
    buf[5] = codec_flags;
    buf[6..8].copy_from_slice(&HELLO_SIZE_V1.to_be_bytes());
    buf[8..16].copy_from_slice(&session_id.to_be_bytes());
    buf
}

/// Write `header` followed by `payload` with vectored I/O until complete.
pub(crate) fn send_all_vectored(
    stream: &mut TcpStream,
    header: &[u8],
    payload: &[u8],
) -> std::io::Result<()> {
    let mut header_off = 0usize;
    let mut payload_off = 0usize;

    while header_off < header.len() || payload_off < payload.len() {
        let mut bufs = [IoSlice::new(&[]), IoSlice::new(&[])];
        let mut count = 0usize;

        if header_off < header.len() {
            bufs[count] = IoSlice::new(&header[header_off..]);
            count += 1;
        }
        if payload_off < payload.len() {
            bufs[count] = IoSlice::new(&payload[payload_off..]);
            count += 1;
        }

        let written = stream.write_vectored(&bufs[..count])?;
        if written == 0 {
            return Err(std::io::Error::new(
                std::io::ErrorKind::BrokenPipe,
                "write_vectored=0",
            ));
        }

        let header_left = header.len() - header_off;
        if written < header_left {
            header_off += written;
            continue;
        }
        header_off = header.len();
        payload_off += written - header_left;
    }

    Ok(())
}
