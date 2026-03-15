use gstreamer as gst;
use gstreamer::prelude::*;

const X265_MAX_BITRATE_KBPS: u32 = 100_000;

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
    if backend == "nvenc265" {
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

    // x265 software fallback.
    let safe_bitrate = bitrate_kbps.min(X265_MAX_BITRATE_KBPS);
    if safe_bitrate != bitrate_kbps {
        println!(
            "[wbeam] clamping x265 bitrate from {} to {} kbps (backend safety)",
            bitrate_kbps, safe_bitrate
        );
    }
    let _ = enc.set_property("bitrate", safe_bitrate);
    let _ = enc.set_property_from_str("speed-preset", "ultrafast");
    let _ = enc.set_property_from_str("tune", "zerolatency");
    set_gop_key_int_max(enc, gop);
    let option_str = if intra_only {
        "bframes=0:no-open-gop=1:scenecut=0:strong-intra-smoothing=0"
    } else {
        "bframes=0:no-open-gop=1:strong-intra-smoothing=0:scenecut=40"
    };
    let _ = enc.set_property("option-string", option_str);
}
