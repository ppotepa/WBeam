package com.wbeam.api;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import com.wbeam.BuildConfig;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;

/**
 * Polls the WBeam daemon's /status, /health, and /metrics endpoints.
 * Holds all daemon state fields so MainActivity can query them without coupling to the poll logic.
 */
@SuppressWarnings({"java:S1068", "java:S1450", "java:S1192"})
public final class StatusPoller {

    private static final String TAG = "WBeamStatusPoller";

    // Keep reconnect/startup fast, but use battery-friendlier cadence otherwise.
    private static final long STATUS_POLL_RECONNECT_MS          = 200L;
    private static final long STATUS_POLL_RECONNECT_MAX_MS      = 2_000L;
    private static final long STATUS_POLL_STREAMING_VISIBLE_MS  = 800L;
    private static final long STATUS_POLL_STREAMING_HIDDEN_MS   = 1_200L;
    private static final long STATUS_POLL_IDLE_MS               = 3_000L;
    private static final long STATUS_POLL_OFFLINE_MS            = 5_000L;
    // /health is mostly static (service + revision), so keep it much less frequent.
    private static final long HEALTH_POLL_INTERVAL_MS  = 20_000L;
    // When HUD is hidden, host metrics don't need full 5 Hz cadence.
    private static final int  METRICS_POLL_EVERY_STREAMING_HIDDEN = 2;
    private static final long STATUS_UI_HEARTBEAT_MS   = 2_000L;
    private static final long ENSURE_DECODER_INTERVAL_MS = 1_000L;
    private static final long AUTO_START_COOLDOWN_BASE_MS = 4_000L;
    private static final long AUTO_START_COOLDOWN_MAX_MS  = 30_000L;

    // ── Daemon state constants ─────────────────────────────────────────────────
    private static final String STATE_IDLE          = "IDLE";
    private static final String STATE_STREAMING     = "STREAMING";
    private static final String STATE_DISCONNECTED  = "DISCONNECTED";
    private static final String STATE_CONNECTING    = "CONNECTING";
    private static final String STATE_STARTING      = "STARTING";
    private static final String STATE_RECONNECTING  = "RECONNECTING";
    private static final String LOG_API_PREFIX      = " api=";
    private static final String METRIC_CONNECTION_MODE = "connection_mode";
    private static final String METHOD_GET = "GET";
    private static final String PATH_STATUS = "/status";
    private static final String PATH_HEALTH = "/health";
    private static final String PATH_METRICS = "/metrics";

    // ── Daemon state (queried by MainActivity via getters) ────────────────────
    private boolean daemonReachable    = false;
    private String  daemonHostName     = "-";
    private String  daemonState        = STATE_IDLE;
    private String  daemonLastError    = "";
    private long    daemonRunId        = 0L;
    private long    daemonUptimeSec    = 0L;
    private String  daemonService      = "-";
    private String  daemonBuildRevision = "-";
    private boolean daemonStateLogInitialized = false;
    private String  daemonStateLogValue = STATE_IDLE;
    private long    daemonRunIdLogValue = 0L;
    private String  daemonLastErrorLogValue = "";
    private String  buildMismatchSnapshot = "";

    // ── Poll bookkeeping ──────────────────────────────────────────────────────
    private volatile boolean statusPollInFlight = false;
    private long    statusPollTick   = 0L;
    private long    lastUiDispatchAtMs = 0L;
    private long    lastEnsureDecoderAtMs = 0L;
    private long    nextHealthPollAtMs = 0L;

    // ── Auto-start state (shared with StreamSessionController via getters/setters) ──
    private long    suppressAutoStartUntil = 0L;
    private boolean autoStartPending       = false;
    private long    lastAutoStartAt        = 0L;
    private int     autoStartFailCount     = 0;
    private int     reconnectPollCount     = 0;

    // ── Infrastructure ────────────────────────────────────────────────────────
    private final Handler         uiHandler;
    private final ExecutorService ioExecutor;
    private final BoolProvider    metricsVisibleProvider;
    private final Callbacks       callbacks;

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            pollAsync();
            uiHandler.postDelayed(this, nextPollDelayMs());
        }
    };

    // ── Callback interface ────────────────────────────────────────────────────

    public interface BoolProvider {
        boolean get();
    }

    public interface Callbacks {
        /** Called on UI thread after a successful poll. */
        @SuppressWarnings("java:S107")
        void onDaemonStatusUpdate(
                boolean reachable,
                boolean wasReachable,
                String  hostName,
                String  daemonState,
                long    runId,
                String  lastError,
                boolean errorChanged,
                long    uptimeSec,
                String  service,
                String  buildRevision,
                JSONObject metrics
        );

        /** Called on UI thread when the daemon is unreachable. */
        void onDaemonOffline(boolean wasReachableBeforeThisPoll, Exception e);

        /** Called on UI thread when auto-start should be triggered. */
        void onAutoStartRequired();

        /** Called on UI thread when auto-start should be suppressed (failed capture). */
        void onAutoStartFailed();

        /** Called on UI thread to start the decoder (STREAMING state confirmed). */
        void ensureDecoderRunning();
    }

    // ── Constructor ───────────────────────────────────────────────────────────

    @SuppressWarnings("java:S107")
    public StatusPoller(
            Handler uiHandler,
            ExecutorService ioExecutor,
            BoolProvider metricsVisibleProvider,
            Callbacks callbacks
    ) {
        this.uiHandler  = uiHandler;
        this.ioExecutor = ioExecutor;
        this.metricsVisibleProvider = metricsVisibleProvider;
        this.callbacks  = callbacks;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void start() {
        uiHandler.removeCallbacks(pollTask);
        uiHandler.post(pollTask);
    }

    public void stop() {
        uiHandler.removeCallbacks(pollTask);
    }

    // ── Auto-start helpers (called by StreamSessionController) ────────────────

    public void suppressAutoStart(long durationMs) {
        suppressAutoStartUntil = SystemClock.elapsedRealtime() + durationMs;
    }

    public void clearAutoStartSuppression() {
        suppressAutoStartUntil = 0L;
    }

    public void permanentlySuppressAutoStart() {
        suppressAutoStartUntil = Long.MAX_VALUE / 2;
    }

    public void setAutoStartPending(boolean pending) {
        autoStartPending = pending;
    }

    public void recordAutoStartAttempt() {
        lastAutoStartAt = SystemClock.elapsedRealtime();
    }

    // ── Daemon state getters (query from UI thread) ────────────────────────────

    public boolean isDaemonReachable()  { return daemonReachable; }
    public String  getDaemonHostName()  { return daemonHostName; }
    public String  getDaemonState()     { return daemonState; }
    public String  getDaemonLastError() { return daemonLastError; }
    public long    getDaemonRunId()     { return daemonRunId; }
    public long    getDaemonUptimeSec() { return daemonUptimeSec; }
    public String  getDaemonService()   { return daemonService; }
    public String  getDaemonBuildRevision() { return daemonBuildRevision; }

    // ── Poll logic ────────────────────────────────────────────────────────────

    @SuppressWarnings("java:S3398")
    private void pollAsync() {
        if (statusPollInFlight) {
            return;
        }
        statusPollInFlight = true;
        long pollTick = ++statusPollTick;
        long nowMs = SystemClock.elapsedRealtime();
        boolean metricsVisible = metricsVisibleProvider != null && metricsVisibleProvider.get();
        boolean fetchHealth = shouldFetchHealth(nowMs);
        boolean fetchMetrics = shouldFetchMetrics(pollTick, metricsVisible, fetchHealth);

        ioExecutor.execute(() -> {
            try {
                JSONObject status = HostApiClient.getStatusWithRetry(HostApiClient.API_RETRY_ATTEMPTS);
                JSONObject health = fetchHealth
                        ? HostApiClient.getHealthWithRetry(HostApiClient.API_RETRY_ATTEMPTS)
                        : null;
                JSONObject metricsPayload = fetchMetrics
                        ? HostApiClient.getMetricsWithRetry(metricsVisible ? HostApiClient.API_RETRY_ATTEMPTS : 1)
                        : null;
                if (health != null) {
                    nextHealthPollAtMs = SystemClock.elapsedRealtime() + HEALTH_POLL_INTERVAL_MS;
                }
                JSONObject metrics = fetchMetrics ? mergeMetricsPayload(metricsPayload) : null;
                uiHandler.post(() -> processStatusResult(status, health, metrics));
            } catch (Exception e) {
                uiHandler.post(() -> processOfflineResult(e));
            } finally {
                statusPollInFlight = false;
            }
        });
    }

    private void processStatusResult(JSONObject status, JSONObject health, JSONObject metrics) {
        long nowMs = SystemClock.elapsedRealtime();
        boolean wasReachable = daemonReachable;
        daemonReachable  = true;

        daemonHostName   = status.optString("host_name", daemonHostName);
        daemonState      = status.optString("state", STATE_IDLE).toUpperCase(Locale.US);
        String newLastError = status.optString("last_error", "");
        boolean errorChanged = !newLastError.equals(daemonLastError);
        daemonLastError  = newLastError;
        daemonRunId      = status.optLong("run_id", daemonRunId);
        daemonUptimeSec  = status.optLong("uptime", daemonUptimeSec);
        daemonService    = health != null ? health.optString("service", daemonService) : daemonService;
        daemonBuildRevision = health != null
            ? health.optString("build_revision", daemonBuildRevision)
            : daemonBuildRevision;
        maybeLogBuildMismatch();

        boolean statusChanged = !daemonStateLogInitialized
                || !daemonState.equals(daemonStateLogValue)
                || daemonRunId != daemonRunIdLogValue
                || !daemonLastError.equals(daemonLastErrorLogValue);
        if (statusChanged) {
            daemonStateLogInitialized = true;
            daemonStateLogValue = daemonState;
            daemonRunIdLogValue = daemonRunId;
            daemonLastErrorLogValue = daemonLastError;
            Log.i(TAG, "daemon state=" + daemonState + " run_id=" + daemonRunId
                    + LOG_API_PREFIX + HostApiClient.API_BASE
                    + (daemonLastError.isEmpty() ? "" : " last_error=" + daemonLastError));
        }

        if (shouldDispatchUiUpdate(wasReachable, statusChanged, errorChanged, metrics, nowMs)) {
            lastUiDispatchAtMs = nowMs;
            callbacks.onDaemonStatusUpdate(
                    true, wasReachable,
                    daemonHostName, daemonState, daemonRunId, daemonLastError, errorChanged,
                    daemonUptimeSec, daemonService, daemonBuildRevision, metrics
            );
        }

        if (STATE_STREAMING.equals(daemonState)) {
            autoStartPending = false;
            autoStartFailCount = 0;
            if (statusChanged || (nowMs - lastEnsureDecoderAtMs) >= ENSURE_DECODER_INTERVAL_MS) {
                lastEnsureDecoderAtMs = nowMs;
                callbacks.ensureDecoderRunning();
            }
            return;
        }

        if (autoStartPending && STATE_IDLE.equals(daemonState) && !isAutoStartSuppressed()) {
            autoStartPending = false;
            autoStartFailCount++;
            permanentlySuppressAutoStart();
            callbacks.onAutoStartFailed();
        }
    }

    private void maybeLogBuildMismatch() {
        String hostRev = daemonBuildRevision == null ? "" : daemonBuildRevision.trim();
        String appRev = BuildConfig.WBEAM_BUILD_REV == null ? "" : BuildConfig.WBEAM_BUILD_REV.trim();
        boolean hostKnown = !hostRev.isEmpty() && !"-".equals(hostRev);
        boolean appKnown = !appRev.isEmpty();
        boolean mismatch = daemonReachable && hostKnown && appKnown && !hostRev.equals(appRev);
        String snapshot = mismatch
                ? "mismatch|" + appRev + "|" + hostRev + "|" + HostApiClient.API_BASE
                : "ok|" + appRev + "|" + hostRev;

        if (!snapshot.equals(buildMismatchSnapshot)) {
            buildMismatchSnapshot = snapshot;
            if (mismatch) {
                Log.e(TAG, "build mismatch app=" + appRev + " host=" + hostRev
                        + LOG_API_PREFIX + HostApiClient.API_BASE
                        + " hint=rebuild host and redeploy APK with same WBEAM_BUILD_REV");
            } else if (daemonReachable && hostKnown && appKnown) {
                Log.i(TAG, "build match restored app=" + appRev + " host=" + hostRev
                        + LOG_API_PREFIX + HostApiClient.API_BASE);
            }
        }
    }

    private void processOfflineResult(Exception e) {
        boolean wasReachable = daemonReachable;
        daemonReachable = false;
        daemonState     = STATE_DISCONNECTED;
        daemonStateLogInitialized = false;
        nextHealthPollAtMs = 0L;
        Log.e(TAG, "daemon poll failed api=" + HostApiClient.API_BASE, e);
        callbacks.onDaemonOffline(wasReachable, e);
    }

    private boolean shouldDispatchUiUpdate(
            boolean wasReachable,
            boolean statusChanged,
            boolean errorChanged,
            JSONObject metrics,
            long nowMs
    ) {
        if (!wasReachable || statusChanged || errorChanged || metrics != null) {
            return true;
        }
        return (nowMs - lastUiDispatchAtMs) >= STATUS_UI_HEARTBEAT_MS;
    }

    private long nextPollDelayMs() {
        if (!daemonReachable) {
            return STATUS_POLL_OFFLINE_MS;
        }
        if (autoStartPending
                || STATE_CONNECTING.equals(daemonState)
                || STATE_STARTING.equals(daemonState)
                || STATE_RECONNECTING.equals(daemonState)) {
            // Exponential backoff: 200 → 400 → 800 → 1600 → 2000 (capped)
            long delay = Math.min(
                    STATUS_POLL_RECONNECT_MS << Math.min(reconnectPollCount, 4),
                    STATUS_POLL_RECONNECT_MAX_MS);
            reconnectPollCount++;
            return delay;
        }
        reconnectPollCount = 0;
        if (STATE_STREAMING.equals(daemonState)) {
            return isMetricsVisible() ? STATUS_POLL_STREAMING_VISIBLE_MS : STATUS_POLL_STREAMING_HIDDEN_MS;
        }
        return STATUS_POLL_IDLE_MS;
    }

    private boolean shouldFetchMetrics(long pollTick, boolean metricsVisible, boolean fetchHealth) {
        if (metricsVisible) {
            return true;
        }
        if (fetchHealth) {
            return false;
        }
        if (STATE_CONNECTING.equals(daemonState)
                || STATE_STARTING.equals(daemonState)
                || STATE_RECONNECTING.equals(daemonState)) {
            return true;
        }
        if (STATE_STREAMING.equals(daemonState)) {
            return (pollTick % METRICS_POLL_EVERY_STREAMING_HIDDEN) == 1L;
        }
        return false;
    }

    private boolean shouldFetchHealth(long nowMs) {
        return !daemonReachable || nowMs >= nextHealthPollAtMs;
    }

    private boolean isMetricsVisible() {
        return metricsVisibleProvider != null && metricsVisibleProvider.get();
    }

    /**
     * /metrics payload is normalized here so UI can consume one metrics object.
     */
    private static JSONObject mergeMetricsPayload(JSONObject payload) {
        if (payload == null) {
            return null;
        }

        JSONObject merged = payload.optJSONObject("metrics");
        if (merged == null) {
            merged = new JSONObject();
        }

        if (payload.has("connection_mode")) {
            putQuietly(
                    merged,
                    METRIC_CONNECTION_MODE,
                    payload.optString(METRIC_CONNECTION_MODE, "live")
            );
        }
        return merged;
    }

    private boolean isAutoStartSuppressed() {
        long nowMs = SystemClock.elapsedRealtime();
        long cooldown = Math.min(
                AUTO_START_COOLDOWN_BASE_MS << Math.min(autoStartFailCount, 3),
                AUTO_START_COOLDOWN_MAX_MS);
        return nowMs < suppressAutoStartUntil
                || (nowMs - lastAutoStartAt) < cooldown;
    }

    private static void putQuietly(JSONObject obj, String key, Object value) {
        if (obj == null || key == null || key.isEmpty()) {
            return;
        }
        try {
            obj.put(key, value);
        } catch (Exception ignored) {
            // ignore malformed payload fragments; keep polling alive
        }
    }
}
