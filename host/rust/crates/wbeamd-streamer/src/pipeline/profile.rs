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

fn profile_with_buffers(
    frame_ns: u64,
    queue_buffers: u32,
    appsink_buffers: u32,
    queue_leaky: &'static str,
    appsink_drop: bool,
    use_videorate: bool,
) -> BufferProfile {
    BufferProfile {
        queue_buffers,
        appsink_buffers,
        queue_leaky,
        appsink_drop,
        appsink_sync: false,
        use_videorate,
        queue_time_ns: frame_ns.saturating_mul(queue_buffers as u64),
    }
}

pub(super) fn buffer_profile(mode: StreamMode, fps: u32, mode_png: bool) -> BufferProfile {
    let frame_ns = (1_000_000_000u64 / fps.max(1) as u64).max(1);
    match mode {
        StreamMode::Ultra => build_ultra_profile(frame_ns, mode_png),
        StreamMode::Stable => build_stable_profile(frame_ns, mode_png),
        StreamMode::Quality => build_quality_profile(frame_ns, mode_png),
    }
}

fn build_ultra_profile(frame_ns: u64, mode_png: bool) -> BufferProfile {
    let queue_buffers = if mode_png { 4 } else { 3 };
    let appsink_buffers = if mode_png { 2 } else { 1 };
    profile_with_buffers(
        frame_ns,
        queue_buffers,
        appsink_buffers,
        "downstream",
        true,
        !mode_png,
    )
}

fn build_stable_profile(frame_ns: u64, mode_png: bool) -> BufferProfile {
    let queue_buffers = if mode_png { 6 } else { 5 };
    let appsink_buffers = if mode_png { 4 } else { 3 };
    profile_with_buffers(
        frame_ns,
        queue_buffers,
        appsink_buffers,
        "upstream",
        false,
        false,
    )
}

fn build_quality_profile(frame_ns: u64, mode_png: bool) -> BufferProfile {
    let queue_buffers = if mode_png { 10 } else { 8 };
    let appsink_buffers = if mode_png { 6 } else { 5 };
    profile_with_buffers(
        frame_ns,
        queue_buffers,
        appsink_buffers,
        "upstream",
        true,
        false,
    )
}
