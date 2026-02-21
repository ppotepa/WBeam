package com.wbeam.api;

import com.wbeam.ClientMetricsSample;

/**
 * Callback interface for H264TcpPlayer and stream events.
 * Implemented by MainActivity to update UI from player callbacks.
 */
public interface StatusListener {
    /** Player state + info + byte throughput changed. */
    void onStatus(String state, String info, long bps);

    /** One-second stats line (fps, drops, queue depths, reconnects). */
    void onStats(String line);

    /** Periodic client-side metrics sample (for adaptive bitrate via /v1/client-metrics). */
    void onClientMetrics(ClientMetricsSample metrics);
}
