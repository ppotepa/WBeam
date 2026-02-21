package com.wbeam.api;

import android.os.SystemClock;

import com.wbeam.BuildConfig;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.EOFException;
import java.io.IOException;
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

    private static final String  HOST         = resolveHost();
    private static final int     CONTROL_PORT = 5001;
    public  static final String  API_BASE     = "http://" + HOST + ":" + CONTROL_PORT;

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
            return "127.0.0.1";
        }
        String trimmed = configured.trim();
        return trimmed.isEmpty() ? "127.0.0.1" : trimmed;
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
        RequestBody body = payload != null
            ? RequestBody.create(JSON_MEDIA_TYPE, payload.toString())
                : null;
        Request request = new Request.Builder()
                .url(API_BASE + path)
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
}
