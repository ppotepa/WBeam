use std::io::{IoSlice, Write};
use std::net::TcpStream;

use wbtp_core::{Flags, MAGIC, VERSION};

const HELLO_MAGIC: &[u8; 4] = b"WBS1";
const HELLO_VERSION: u8 = 0x01;

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

/// Build a 16-byte WBTP HELLO greeting for a new connection.
#[inline]
pub(crate) fn build_hello(session_id: u64, codec_flags: u8) -> [u8; 16] {
    let mut buf = [0u8; 16];
    buf[0..4].copy_from_slice(HELLO_MAGIC);
    buf[4] = HELLO_VERSION;
    buf[5] = codec_flags;
    buf[6..8].copy_from_slice(&16u16.to_be_bytes());
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
