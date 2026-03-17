use anyhow::{Context, Result};
use gstreamer as gst;
use gstreamer::prelude::*;

use super::{build_element, debug_caps};

pub(super) struct DebugBranchElements {
    qdbg: gst::Element,
    vrdbg: gst::Element,
    convdbg: gst::Element,
    capsdbg: gst::Element,
    jpeg: gst::Element,
    multi: gst::Element,
}

impl DebugBranchElements {
    pub(super) fn new(debug_fps: u32, debug_dir: &str) -> Result<Self> {
        std::fs::create_dir_all(debug_dir)
            .with_context(|| format!("create debug dir {debug_dir}"))?;

        let qdbg = build_element("queue", "qdbg")?;
        let vrdbg = build_element("videorate", "vrdbg")?;
        let convdbg = build_element("videoconvert", "convdbg")?;
        let capsdbg = build_element("capsfilter", "capsdbg")?;
        let jpeg = build_element("jpegenc", "jpegdbg")?;
        let multi = build_element("multifilesink", "filesdbg")?;

        configure_debug_queue(&qdbg);
        let _ = capsdbg.set_property("caps", &debug_caps(debug_fps));
        multi.set_property("location", format!("{debug_dir}/frame-%06d.jpg"));
        multi.set_property("sync", false);
        multi.set_property("async", false);
        multi.set_property("post-messages", false);
        multi.set_property("max-files", 300u32);
        let _ = jpeg.set_property("quality", 70i32);

        Ok(Self {
            qdbg,
            vrdbg,
            convdbg,
            capsdbg,
            jpeg,
            multi,
        })
    }

    pub(super) fn attach_to_pipeline(
        self,
        pipeline: &gst::Pipeline,
        tee: &gst::Element,
        link_tee_branch: fn(&gst::Element, &gst::Element, &str) -> Result<()>,
    ) -> Result<()> {
        pipeline.add_many([
            &self.qdbg,
            &self.vrdbg,
            &self.convdbg,
            &self.capsdbg,
            &self.jpeg,
            &self.multi,
        ])?;
        link_tee_branch(tee, &self.qdbg, "qdbg")?;
        gst::Element::link_many([
            &self.qdbg,
            &self.vrdbg,
            &self.convdbg,
            &self.capsdbg,
            &self.jpeg,
            &self.multi,
        ])?;
        Ok(())
    }
}

fn configure_debug_queue(qdbg: &gst::Element) {
    let _ = qdbg.set_property("max-size-buffers", 1u32);
    let _ = qdbg.set_property("max-size-bytes", 0u32);
    let _ = qdbg.set_property("max-size-time", 200_000_000u64);
    let _ = qdbg.set_property_from_str("leaky", "downstream");
}
