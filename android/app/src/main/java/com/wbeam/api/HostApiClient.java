package com.wbeam.api;

import android.os.SystemClock;

import com.wbeam.BuildConfig;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Thin HTTP client utilities for the WBeam control API (port 5001).
 * Holds shared OkHttp instances and provides retry-aware request helpers.
 */
public final class HostApiClient {
    private static final String LOOPBACK_HOST = "127.0.0.1";
    private static final String LOCAL_STATE_STREAMING = "STREAMING";

    private static final String API_IMPL = BuildConfig.WBEAM_API_IMPL == null
            ? "host"
            : BuildConfig.WBEAM_API_IMPL.trim().toLowerCase(Locale.US);
    private static final boolean API_IMPL_LOCAL = "local".equals(API_IMPL);

    private static final String HOST = resolveHost();
    private static final int CONTROL_PORT = BuildConfig.WBEAM_CONTROL_PORT;
    private static final int STREAM_PORT = BuildConfig.WBEAM_STREAM_PORT;
    private static final String TARGET_SERIAL = resolveTargetSerial();
    public static final String API_BASE = "http://" + HOST + ":" + CONTROL_PORT;

    public  static final int  API_RETRY_ATTEMPTS      = 2;
    public  static final long API_RETRY_BASE_DELAY_MS = 300L;

    public static final MediaType JSON_MEDIA_TYPE =
            MediaType.parse("application/json; charset=utf-8");

    private static final ConnectionPool HTTP_CONNECTION_POOL =
            new ConnectionPool(2, 30, TimeUnit.SECONDS);

    public static final OkHttpClient API_HTTP = new OkHttpClient.Builder()
            .connectTimeout(1500, TimeUnit.MILLISECONDS)
            .readTimeout(1500, TimeUnit.MILLISECONDS)
            .writeTimeout(1500, TimeUnit.MILLISECONDS)
            .connectionPool(HTTP_CONNECTION_POOL)
            .retryOnConnectionFailure(true)
            .build();

    public static final OkHttpClient SPEEDTEST_HTTP = API_HTTP.newBuilder()
            .connectTimeout(2500, TimeUnit.MILLISECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(2500, TimeUnit.MILLISECONDS)
            .build();

    // Singleton — no instances needed.
    private HostApiClient() {}

    private static String resolveHost() {
        String configured = BuildConfig.WBEAM_API_HOST;
        if (configured == null || configured.trim().isEmpty()) {
            configured = BuildConfig.WBEAM_HOST;
        }
        if (configured == null) {
            return LOOPBACK_HOST;
        }
        String trimmed = configured.trim();
        return trimmed.isEmpty() ? LOOPBACK_HOST : trimmed;
    }

    private static String resolveTargetSerial() {
        String configured = BuildConfig.WBEAM_ANDROID_SERIAL;
        if (configured == null) {
            return "";
        }
        String trimmed = configured.trim();
        return trimmed.isEmpty() ? "" : trimmed;
    }

    private static String appendSessionQuery(String path) {
        String normalized = normalizePath(path);
        StringBuilder sb = new StringBuilder(normalized);
        String separator = normalized.contains("?") ? "&" : "?";
        sb.append(separator).append("stream_port=").append(STREAM_PORT);
        if (!TARGET_SERIAL.isEmpty()) {
            sb.append("&serial=").append(TARGET_SERIAL);
        }
        return sb.toString();
    }

    /**
     * Execute an HTTP request against the control API with exponential-backoff retry.
     */
    public static JSONObject apiRequestWithRetry(
            String method,
            String path,
            JSONObject payload,
            int attempts
    ) throws IOException, JSONException {
        if (API_IMPL_LOCAL) {
            return apiRequest(method, path, payload);
        }
        IOException lastIo = null;
        for (int i = 0; i < Math.max(1, attempts); i++) {
            try {
                return apiRequest(method, path, payload);
            } catch (IOException io) {
                lastIo = io;
                if (isLikelyStaleHttpConnection(io)) {
                    API_HTTP.connectionPool().evictAll();
                }
                if (i == attempts - 1) {
                    break;
                }
                long baseDelay = Math.min(5000L, API_RETRY_BASE_DELAY_MS * (1L << i));
                long jitterBound = Math.max(1L, baseDelay / 4L + 1L);
                long jitter = (long) (Math.random() * jitterBound);
                SystemClock.sleep(baseDelay + jitter);
            }
        }
        throw lastIo != null ? lastIo : new IOException("request failed");
    }

    /**
     * Execute a single HTTP request against the control API.
     */
    public static JSONObject apiRequest(
            String method,
            String path,
            JSONObject payload
    ) throws IOException, JSONException {
        if (API_IMPL_LOCAL) {
            return localApiRequest(method, path, payload);
        }

        RequestBody body = payload != null
            ? RequestBody.create(JSON_MEDIA_TYPE, payload.toString())
                : null;
        String sessionPath = appendSessionQuery(path);
        Request request = new Request.Builder()
                .url(API_BASE + sessionPath)
                .header("Accept", "application/json")
                .method(method, body)
                .build();

        try (Response response = API_HTTP.newCall(request).execute()) {
            int code = response.code();
            ResponseBody responseBody = response.body();
            String text = responseBody != null ? responseBody.string().trim() : "";
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + ": " + text);
            }
            return text.isEmpty() ? new JSONObject() : new JSONObject(text);
        }
    }

    /**
     * Perform a lightweight transport probe by downloading a fixed payload from
     * `/v1/speedtest`. Returns elapsed milliseconds when successful.
     */
    public static long runTransportProbeMs(int mb) throws IOException {
        int sizeMb = Math.max(1, Math.min(8, mb));
        String url = API_BASE + appendSessionQuery("/v1/speedtest?mb=" + sizeMb);

        Request request = new Request.Builder()
                .url(url)
                .header("Accept", "application/octet-stream")
                .get()
                .build();

        long startedAt = SystemClock.elapsedRealtime();
        try (Response response = SPEEDTEST_HTTP.newCall(request).execute()) {
            int code = response.code();
            if (code < 200 || code >= 300) {
                throw new IOException("transport probe HTTP " + code);
            }

            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("transport probe empty body");
            }

            long bytes = 0L;
            byte[] buffer = new byte[16 * 1024];
            try (InputStream in = body.byteStream()) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    bytes += read;
                }
            }

            long expectedMin = (long) sizeMb * 1024L * 1024L;
            if (bytes < expectedMin) {
                throw new IOException("transport probe short read " + bytes + "B");
            }
        }
        return Math.max(1L, SystemClock.elapsedRealtime() - startedAt);
    }

    /**
     * Returns true if the IOException looks like a stale keep-alive connection.
     * In that case the connection pool should be evicted before retrying.
     */
    public static boolean isLikelyStaleHttpConnection(IOException io) {
        String message = io.getMessage();
        if (message == null) {
            return io instanceof EOFException;
        }
        String m = message.toLowerCase(Locale.US);
        return io instanceof EOFException
                || m.contains("unexpected end of stream")
                || m.contains("\\n not found")
                || m.contains("end of stream");
    }

    private static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return "/";
        }
        return path.startsWith("/") ? path : ("/" + path);
    }

    private static synchronized JSONObject localApiRequest(
            String method,
            String path,
            JSONObject payload
    ) throws JSONException {
        final String m = method == null ? "GET" : method.toUpperCase(Locale.US);
        final String p0 = normalizePath(path);
        final int q = p0.indexOf('?');
        final String p = q > 0 ? p0.substring(0, q) : p0;
        final long nowSec = System.currentTimeMillis() / 1000L;
        final long nowMs = System.currentTimeMillis();

        if ("POST".equals(m) && "/start".equals(p)) {
            resetLocalApiCounters(nowSec);
            LocalApiState.state = "STARTING";
            LocalApiState.runId++;
        } else if ("POST".equals(m) && ("/stop".equals(p) || "/apply".equals(p))) {
            resetLocalApiCounters(nowSec);
            LocalApiState.state = "IDLE";
        } else if ("POST".equals(m) && "/v1/client-metrics".equals(p)) {
            applyClientMetrics(payload, nowSec, nowMs);
            return new JSONObject();
        }

        refreshLocalApiState(nowSec, nowMs);

        if ("GET".equals(m) && ("/status".equals(p) || "/health".equals(p))) {
            JSONObject out = buildStatusOrHealthResponse(nowSec);
            if ("/health".equals(p)) {
                out.put("service", "android-local-api");
                out.put("stream_process_alive", false);
            }
            return out;
        }

        if ("GET".equals(m) && "/metrics".equals(p)) {
            JSONObject out = new JSONObject();
            out.put("metrics", buildMetricsPayload(nowSec));
            out.put("ok", true);
            return out;
        }

        return new JSONObject();
    }

    private static void resetLocalApiCounters(long nowSec) {
        LocalApiState.lastError = "";
        LocalApiState.startedAtSec = nowSec;
        LocalApiState.lastClientMetricAtMs = 0L;
        LocalApiState.lastFlowAtMs = 0L;
        LocalApiState.firstFlowAtSec = 0L;
        LocalApiState.latestRecvFps = 0.0;
        LocalApiState.latestPresentFps = 0.0;
        LocalApiState.latestRecvBps = 0L;
    }

    private static void applyClientMetrics(JSONObject payload, long nowSec, long nowMs) {
        double recvFps = payload != null ? payload.optDouble("recv_fps", 0.0) : 0.0;
        double presentFps = payload != null ? payload.optDouble("present_fps", 0.0) : 0.0;
        long recvBps = payload != null ? payload.optLong("recv_bps", 0L) : 0L;
        LocalApiState.latestRecvFps = recvFps;
        LocalApiState.latestPresentFps = presentFps;
        LocalApiState.latestRecvBps = recvBps;
        LocalApiState.lastClientMetricAtMs = nowMs;

        boolean flowing = recvBps > 0L || recvFps >= 1.0 || presentFps >= 1.0;
        if (flowing) {
            LocalApiState.lastFlowAtMs = nowMs;
            if (LocalApiState.firstFlowAtSec == 0L) {
                LocalApiState.firstFlowAtSec = nowSec;
            }
            LocalApiState.state = LOCAL_STATE_STREAMING;
            return;
        }
        if (!"IDLE".equals(LocalApiState.state)) {
            long sinceStartSec = Math.max(0L, nowSec - LocalApiState.startedAtSec);
            LocalApiState.state = sinceStartSec < 3L ? "STARTING" : "RECONNECTING";
        }
    }

    private static void refreshLocalApiState(long nowSec, long nowMs) {
        if (LOCAL_STATE_STREAMING.equals(LocalApiState.state)) {
            long idleMs = LocalApiState.lastFlowAtMs > 0L
                    ? (nowMs - LocalApiState.lastFlowAtMs)
                    : Long.MAX_VALUE;
            if (idleMs > 2500L) {
                LocalApiState.state = "RECONNECTING";
            }
            return;
        }
        if (!"IDLE".equals(LocalApiState.state)) {
            long sinceStartSec = Math.max(0L, nowSec - LocalApiState.startedAtSec);
            if (sinceStartSec >= 3L) {
                LocalApiState.state = "RECONNECTING";
            }
        }
    }

    private static JSONObject buildStatusOrHealthResponse(long nowSec) throws JSONException {
        JSONObject out = new JSONObject();
        out.put("state", LocalApiState.state);
        out.put("host_name", LocalApiState.HOST_NAME);
        out.put("run_id", LocalApiState.runId);
        out.put("last_error", LocalApiState.lastError);
        out.put("uptime", Math.max(0L, nowSec - LocalApiState.startedAtSec));
        out.put("ok", true);
        return out;
    }

    private static JSONObject buildMetricsPayload(long nowSec) throws JSONException {
        JSONObject metrics = new JSONObject();
        metrics.put("frame_in", 0);
        metrics.put("frame_out", 0);
        metrics.put("drops", 0);
        metrics.put("reconnects", 0);
        metrics.put("bitrate_actual_bps", LocalApiState.latestRecvBps);
        long streamUptimeSec = 0L;
        if (LOCAL_STATE_STREAMING.equals(LocalApiState.state)
                && LocalApiState.firstFlowAtSec > 0L
                && nowSec >= LocalApiState.firstFlowAtSec) {
            streamUptimeSec = nowSec - LocalApiState.firstFlowAtSec;
        }
        metrics.put("stream_uptime_sec", streamUptimeSec);

        JSONObject kpi = new JSONObject();
        kpi.put("target_fps", 0.0);
        kpi.put("present_fps", LocalApiState.latestPresentFps);
        kpi.put("frametime_ms_p95", 0.0);
        kpi.put("decode_time_ms_p95", 0.0);
        kpi.put("render_time_ms_p95", 0.0);
        kpi.put("e2e_latency_ms_p95", 0.0);
        metrics.put("kpi", kpi);

        JSONObject latestClient = new JSONObject();
        latestClient.put("transport_queue_depth", 0);
        latestClient.put("decode_queue_depth", 0);
        latestClient.put("render_queue_depth", 0);
        metrics.put("latest_client_metrics", latestClient);

        JSONObject queueLimits = new JSONObject();
        queueLimits.put("transport_queue_max", 3);
        queueLimits.put("decode_queue_max", 2);
        queueLimits.put("render_queue_max", 1);
        metrics.put("queue_limits", queueLimits);
        return metrics;
    }

    private static final class LocalApiState {
        private static String state = "IDLE";
        private static String lastError = "";
        private static long runId = 0L;
        private static long startedAtSec = System.currentTimeMillis() / 1000L;
        private static long firstFlowAtSec = 0L;
        private static long lastClientMetricAtMs = 0L;
        private static long lastFlowAtMs = 0L;
        private static double latestRecvFps = 0.0;
        private static double latestPresentFps = 0.0;
        private static long latestRecvBps = 0L;
        private static final String HOST_NAME = "android-local";
    }
}
