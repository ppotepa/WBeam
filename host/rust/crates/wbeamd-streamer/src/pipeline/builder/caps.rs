use gstreamer as gst;

use crate::cli::ResolvedConfig;

use super::runtime::PipelineRuntime;

pub(super) fn source_hint_caps() -> gst::Caps {
    gst::Caps::builder("video/x-raw").build()
}

pub(super) fn raw_caps(cfg: &ResolvedConfig, runtime: &PipelineRuntime) -> gst::Caps {
    let mut caps = gst::Caps::builder("video/x-raw")
        .field("format", runtime.raw_format)
        .field("width", cfg.width as i32)
        .field("height", cfg.height as i32);
    if runtime.profile.use_videorate {
        caps = caps.field("framerate", gst::Fraction::new(cfg.fps as i32, 1));
    }
    caps.build()
}

pub(super) fn sink_caps(runtime: &PipelineRuntime, framed: bool) -> gst::Caps {
    if runtime.mode_png {
        gst::Caps::builder("image/png").build()
    } else {
        gst::Caps::builder(runtime.sink_media_type())
            .field("stream-format", "byte-stream")
            .field("alignment", if framed { "au" } else { "nal" })
            .build()
    }
}

pub(super) fn debug_caps(debug_fps: u32) -> gst::Caps {
    gst::Caps::builder("video/x-raw")
        .field("format", "I420")
        .field("framerate", gst::Fraction::new(debug_fps as i32, 1))
        .build()
}
