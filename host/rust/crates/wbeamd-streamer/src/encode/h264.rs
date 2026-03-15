use gstreamer as gst;
use gstreamer::prelude::*;

fn set_gop_key_int_max(enc: &gst::Element, gop_value: u32) {
    if enc.find_property("key-int-max").is_none() {
        println!(
            "[wbeam] WARN: encoder backend has no 'key-int-max' property; GOP property override skipped"
        );
        return;
    }
    let _ = enc.set_property("key-int-max", gop_value);
}

pub(super) fn configure(
    enc: &gst::Element,
    backend: &str,
    bitrate_kbps: u32,
    nv_preset: &str,
    intra_only: bool,
    gop: u32,
) {
    if backend == "nvenc264" {
        let _ = enc.set_property("bitrate", bitrate_kbps);
        let _ = enc.set_property("max-bitrate", bitrate_kbps * 3);
        let _ = enc.set_property_from_str("rc-mode", "vbr");
        let _ = enc.set_property_from_str("preset", nv_preset);
        let _ = enc.set_property("gop-size", gop as i32);
        let _ = enc.set_property("bframes", 0u32);
        let _ = enc.set_property("zerolatency", true);
        let _ = enc.set_property("aud", true);
        let _ = enc.set_property("repeat-sequence-header", true);
        return;
    }

    // x264 software fallback.
    let _ = enc.set_property("bitrate", bitrate_kbps);
    let _ = enc.set_property_from_str("speed-preset", "ultrafast");
    let _ = enc.set_property_from_str("tune", "zerolatency");
    let _ = enc.set_property("byte-stream", true);
    println!(
        "[wbeam] x264 config: speed-preset=ultrafast tune=zerolatency bframes=0 cabac=0 ref=1"
    );
    set_gop_key_int_max(enc, gop);
    let option_str = if intra_only {
        "bframes=0:cabac=0:ref=1:8x8dct=0:no-open-gop=1:scenecut=0"
    } else {
        "bframes=0:cabac=0:ref=1:8x8dct=0:no-open-gop=1:scenecut=40"
    };
    let _ = enc.set_property("option-string", option_str);
}
