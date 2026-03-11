package com.wbeam.api;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;

import com.wbeam.BuildConfig;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutorService;

/**
 * Polls the WBeam daemon's /status, /health, and /metrics endpoints every STATUS_POLL_MS.
 * Holds all daemon state fields so MainActivity can query them without coupling to the poll logic.
 */
public final class StatusPoller {

    private static final String TAG = "WBeamStatusPoller";

    private static final long STATUS_POLL_MS           = 3_000L;
    private static final int  HEALTH_POLL_EVERY        = 3;
    private static final long AUTO_START_COOLDOWN_MS   = 4_000L;

    // ── Daemon state (queried by MainActivity via getters) ────────────────────
    private boolean daemonReachable    = false;
    private String  daemonHostName     = "-";
    private String  daemonState        = "IDLE";
    private String  daemonLastError    = "";
    private long    daemonRunId        = 0L;
    private long    daemonUptimeSec    = 0L;
    private String  daemonService      = "-";
    private String  daemonBuildRevision = "-";
    private String  daemonStateSnapshot = "";
    private String  buildMismatchSnapshot = "";

    // ── Poll bookkeeping ──────────────────────────────────────────────────────
    private volatile boolean statusPollInFlight = false;
    private long    statusPollTick   = 0L;

    // ── Auto-start state (shared with StreamSessionController via getters/setters) ──
    private long    suppressAutoStartUntil = 0L;
    private boolean autoStartPending       = false;
    private long    lastAutoStartAt        = 0L;

    // ── Infrastructure ────────────────────────────────────────────────────────
    private final Handler         uiHandler;
    private final ExecutorService ioExecutor;
    private final Callbacks       callbacks;

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            pollAsync();
            uiHandler.postDelayed(this, STATUS_POLL_MS);
        }
    };

    // ── Callback interface ────────────────────────────────────────────────────

    public interface Callbacks {
        /** Called on UI thread after a successful poll. */
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

    public StatusPoller(Handler uiHandler, ExecutorService ioExecutor, Callbacks callbacks) {
        this.uiHandler  = uiHandler;
        this.ioExecutor = ioExecutor;
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

    private void pollAsync() {
        if (statusPollInFlight) {
            return;
        }
        statusPollInFlight = true;
        long pollTick = ++statusPollTick;
        boolean fetchHealth = (pollTick % HEALTH_POLL_EVERY) == 1;

        ioExecutor.execute(() -> {
            try {
                JSONObject status = HostApiClient.apiRequestWithRetry(
                        "GET", "/status", null, HostApiClient.API_RETRY_ATTEMPTS);
                JSONObject health = fetchHealth
                        ? HostApiClient.apiRequestWithRetry(
                                "GET", "/health", null, HostApiClient.API_RETRY_ATTEMPTS)
                        : null;
                JSONObject metricsPayload = HostApiClient.apiRequestWithRetry(
                        "GET", "/metrics", null, HostApiClient.API_RETRY_ATTEMPTS);
                JSONObject metrics = mergeMetricsPayload(metricsPayload);
                uiHandler.post(() -> processStatusResult(status, health, metrics));
            } catch (Exception e) {
                uiHandler.post(() -> processOfflineResult(e));
            } finally {
                statusPollInFlight = false;
            }
        });
    }

    private void processStatusResult(JSONObject status, JSONObject health, JSONObject metrics) {
        boolean wasReachable = daemonReachable;
        daemonReachable  = true;

        daemonHostName   = status.optString("host_name", daemonHostName);
        daemonState      = status.optString("state", "IDLE").toUpperCase(Locale.US);
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

        String newSnapshot = daemonState + "|" + daemonRunId + "|" + daemonLastError;
        if (!newSnapshot.equals(daemonStateSnapshot)) {
            daemonStateSnapshot = newSnapshot;
            Log.i(TAG, "daemon state=" + daemonState + " run_id=" + daemonRunId
                    + " api=" + HostApiClient.API_BASE
                    + (daemonLastError.isEmpty() ? "" : " last_error=" + daemonLastError));
        }

        callbacks.onDaemonStatusUpdate(
                true, wasReachable,
                daemonHostName, daemonState, daemonRunId, daemonLastError, errorChanged,
            daemonUptimeSec, daemonService, daemonBuildRevision, metrics
        );

        if ("STREAMING".equals(daemonState)) {
            autoStartPending = false;
            callbacks.ensureDecoderRunning();
            return;
        }

        if (autoStartPending && "IDLE".equals(daemonState)) {
            autoStartPending = false;
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
                        + " api=" + HostApiClient.API_BASE
                        + " hint=rebuild host and redeploy APK with same WBEAM_BUILD_REV");
            } else if (daemonReachable && hostKnown && appKnown) {
                Log.i(TAG, "build match restored app=" + appRev + " host=" + hostRev
                        + " api=" + HostApiClient.API_BASE);
            }
        }
    }

    private void processOfflineResult(Exception e) {
        boolean wasReachable = daemonReachable;
        daemonReachable = false;
        daemonState     = "DISCONNECTED";
        Log.e(TAG, "daemon poll failed api=" + HostApiClient.API_BASE, e);
        callbacks.onDaemonOffline(wasReachable, e);
    }

    /**
     * /metrics payload has mixed shape:
     *  - canonical runtime metrics under "metrics"
     *  - trainer HUD envelope fields at top-level (trainer_hud_*)
     *
     * UI expects one object, so we flatten top-level trainer fields into
     * the nested metrics object.
     */
    private static JSONObject mergeMetricsPayload(JSONObject payload) {
        if (payload == null) {
            return null;
        }

        JSONObject merged = payload.optJSONObject("metrics");
        if (merged == null) {
            merged = new JSONObject();
        }

        if (payload.has("trainer_hud_active")) {
            putQuietly(merged, "trainer_hud_active", payload.optBoolean("trainer_hud_active", false));
        }
        if (payload.has("trainer_hud_text")) {
            putQuietly(merged, "trainer_hud_text", payload.optString("trainer_hud_text", ""));
        }
        if (payload.has("trainer_hud_json")) {
            putQuietly(merged, "trainer_hud_json", payload.opt("trainer_hud_json"));
        }
        return merged;
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
