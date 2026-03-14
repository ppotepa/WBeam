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

fn buffer_counts(mode: StreamMode, mode_png: bool) -> (u32, u32) {
    match mode {
        StreamMode::Ultra if mode_png => (4, 2),
        StreamMode::Ultra => (3, 1),
        StreamMode::Stable if mode_png => (12, 10),
        StreamMode::Stable => (10, 8),
        StreamMode::Quality if mode_png => (20, 16),
        StreamMode::Quality => (24, 20),
    }
}

fn mode_settings(mode: StreamMode, mode_png: bool) -> (&'static str, bool, bool, bool) {
    match mode {
        StreamMode::Ultra => ("downstream", true, false, !mode_png),
        StreamMode::Stable => ("no", false, false, false),
        StreamMode::Quality => ("no", false, true, false),
    }
}

pub(super) fn buffer_profile(mode: StreamMode, fps: u32, mode_png: bool) -> BufferProfile {
    let frame_ns = (1_000_000_000u64 / fps.max(1) as u64).max(1);
    let (queue_buffers, appsink_buffers) = buffer_counts(mode, mode_png);
    let (queue_leaky, appsink_drop, appsink_sync, use_videorate) = mode_settings(mode, mode_png);

    BufferProfile {
        queue_buffers,
        appsink_buffers,
        queue_leaky,
        appsink_drop,
        appsink_sync,
        use_videorate,
        queue_time_ns: frame_ns.saturating_mul(queue_buffers as u64),
    }
}
