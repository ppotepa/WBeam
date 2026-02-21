package com.wbeam;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Snapshot of client-side streaming metrics collected each second by H264TcpPlayer.
 * Sent to host via POST /v1/client-metrics for adaptive bitrate decisions.
 */
public final class ClientMetricsSample {
    public final double recvFps;
    public final double decodeFps;
    public final double presentFps;
    public final long   recvBps;
    public final double decodeMsP50;
    public final double decodeMsP95;
    public final double renderMsP95;
    public final double e2eP50;
    public final double e2eP95;
    public final int    transportQueueDepth;
    public final int    decodeQueueDepth;
    public final int    renderQueueDepth;
    public final int    jitterBufferFrames;
    public final long   droppedFrames;
    public final long   tooLateFrames;
    public final long   timestampMs;
    public final long   traceId; // (sessionConnectId<<32)|sampleSeq

    public ClientMetricsSample(
            double recvFps,
            double decodeFps,
            double presentFps,
            long   recvBps,
            double decodeMsP50,
            double decodeMsP95,
            double renderMsP95,
            double e2eP50,
            double e2eP95,
            int    transportQueueDepth,
            int    decodeQueueDepth,
            int    renderQueueDepth,
            int    jitterBufferFrames,
            long   droppedFrames,
            long   tooLateFrames,
            long   traceId
    ) {
        this.recvFps             = recvFps;
        this.decodeFps           = decodeFps;
        this.presentFps          = presentFps;
        this.recvBps             = recvBps;
        this.decodeMsP50         = decodeMsP50;
        this.decodeMsP95         = decodeMsP95;
        this.renderMsP95         = renderMsP95;
        this.e2eP50              = e2eP50;
        this.e2eP95              = e2eP95;
        this.transportQueueDepth = transportQueueDepth;
        this.decodeQueueDepth    = decodeQueueDepth;
        this.renderQueueDepth    = renderQueueDepth;
        this.jitterBufferFrames  = jitterBufferFrames;
        this.droppedFrames       = droppedFrames;
        this.tooLateFrames       = tooLateFrames;
        this.traceId             = traceId;
        this.timestampMs         = System.currentTimeMillis();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("recv_fps",             recvFps);
        json.put("decode_fps",           decodeFps);
        json.put("present_fps",          presentFps);
        json.put("recv_bps",             recvBps);
        json.put("decode_time_ms_p50",   decodeMsP50);
        json.put("decode_time_ms_p95",   decodeMsP95);
        json.put("render_time_ms_p95",   renderMsP95);
        json.put("e2e_latency_ms_p50",   e2eP50);
        json.put("e2e_latency_ms_p95",   e2eP95);
        json.put("transport_queue_depth",transportQueueDepth);
        json.put("decode_queue_depth",   decodeQueueDepth);
        json.put("render_queue_depth",   renderQueueDepth);
        json.put("jitter_buffer_frames", jitterBufferFrames);
        json.put("dropped_frames",       droppedFrames);
        json.put("too_late_frames",      tooLateFrames);
        json.put("timestamp_ms",         timestampMs);
        json.put("trace_id",             traceId);
        return json;
    }
}
