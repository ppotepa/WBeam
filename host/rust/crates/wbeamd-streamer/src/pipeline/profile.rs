use crate::cli::StreamMode;

pub(super) struct BufferProfile {
    pub(super) queue_buffers: u32,
    pub(super) appsink_buffers: u32,
    pub(super) queue_leaky: &'static str,
    pub(super) appsink_drop: bool,
    pub(super) appsink_sync: bool,
    pub(super) use_videorate: bool,
    pub(super) queue_time_ns: u64,
}

pub(super) fn buffer_profile(mode: StreamMode, fps: u32, mode_png: bool) -> BufferProfile {
    let frame_ns = (1_000_000_000u64 / fps.max(1) as u64).max(1);
    match mode {
        StreamMode::Ultra => {
            let queue_buffers = if mode_png { 4 } else { 3 };
            let appsink_buffers = if mode_png { 2 } else { 1 };
            BufferProfile {
                queue_buffers,
                appsink_buffers,
                queue_leaky: "downstream",
                appsink_drop: true,
                appsink_sync: false,
                use_videorate: !mode_png,
                queue_time_ns: frame_ns.saturating_mul(queue_buffers as u64),
            }
        }
        StreamMode::Stable => {
            let queue_buffers = if mode_png { 12 } else { 10 };
            let appsink_buffers = if mode_png { 10 } else { 8 };
            BufferProfile {
                queue_buffers,
                appsink_buffers,
                queue_leaky: "upstream",
                appsink_drop: false,
                appsink_sync: false,
                use_videorate: false,
                queue_time_ns: frame_ns.saturating_mul(queue_buffers as u64),
            }
        }
        StreamMode::Quality => {
            let queue_buffers = if mode_png { 20 } else { 24 };
            let appsink_buffers = if mode_png { 16 } else { 20 };
            BufferProfile {
                queue_buffers,
                appsink_buffers,
                queue_leaky: "upstream",
                appsink_drop: false,
                appsink_sync: true,
                use_videorate: false,
                queue_time_ns: frame_ns.saturating_mul(queue_buffers as u64),
            }
        }
    }
}
