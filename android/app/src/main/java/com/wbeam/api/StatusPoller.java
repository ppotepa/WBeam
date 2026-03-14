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
@SuppressWarnings("java:S3398")
public final class StatusPoller {

    private static final String TAG = "WBeamStatusPoller";
    private static final String API_SUFFIX = " api=";
    private static final String TRAINER_HUD_ACTIVE = "trainer_hud_active";
    private static final String TRAINER_HUD_TEXT = "trainer_hud_text";
    private static final String TRAINER_HUD_JSON = "trainer_hud_json";
    private static final String CONNECTION_MODE = "connection_mode";

    // 5 Hz telemetry/status polling for responsive HUD updates.
    private static final long STATUS_POLL_MS           = 200L;
    // Keep /health lower-frequency to avoid unnecessary overhead.
    private static final int  HEALTH_POLL_EVERY        = 16;
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
            if (statusPollInFlight) {
                uiHandler.postDelayed(this, STATUS_POLL_MS);
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
            uiHandler.postDelayed(this, STATUS_POLL_MS);
        }
    };

    @SuppressWarnings("java:S107")
    public interface Callbacks {
        /** Called on UI thread after a successful poll. */
        void onDaemonStatusUpdate(DaemonStatusSnapshot snapshot);

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
                    + API_SUFFIX + HostApiClient.API_BASE
                    + (daemonLastError.isEmpty() ? "" : " last_error=" + daemonLastError));
        }

        DaemonStatusSnapshot snapshot = DaemonStatusSnapshot.builder()
                .reachable(true)
                .wasReachable(wasReachable)
                .hostName(daemonHostName)
                .daemonState(daemonState)
                .runId(daemonRunId)
                .lastError(daemonLastError)
                .errorChanged(errorChanged)
                .uptimeSec(daemonUptimeSec)
                .service(daemonService)
                .buildRevision(daemonBuildRevision)
                .metrics(metrics)
                .build();
        callbacks.onDaemonStatusUpdate(snapshot);

        if ("STREAMING".equals(daemonState)) {
            autoStartPending = false;
            callbacks.ensureDecoderRunning();
            return;
        }

        if (!autoStartPending
                && ("STARTING".equals(daemonState) || "RECONNECTING".equals(daemonState))
                && canTriggerAutoStartNow()) {
            autoStartPending = true;
            lastAutoStartAt = SystemClock.elapsedRealtime();
            callbacks.onAutoStartRequired();
            return;
        }

        if (autoStartPending && "IDLE".equals(daemonState)) {
            autoStartPending = false;
            permanentlySuppressAutoStart();
            callbacks.onAutoStartFailed();
        }
    }

    private boolean canTriggerAutoStartNow() {
        long now = SystemClock.elapsedRealtime();
        if (now < suppressAutoStartUntil) {
            return false;
        }
        return (now - lastAutoStartAt) >= AUTO_START_COOLDOWN_MS;
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
                        + API_SUFFIX + HostApiClient.API_BASE);
            } else if (daemonReachable && hostKnown && appKnown) {
                Log.i(TAG, "build match restored app=" + appRev + " host=" + hostRev
                        + API_SUFFIX + HostApiClient.API_BASE);
            }
        }
    }

    private void processOfflineResult(Exception e) {
        boolean wasReachable = daemonReachable;
        daemonReachable = false;
        daemonState     = "DISCONNECTED";
        Log.e(TAG, "daemon poll failed" + API_SUFFIX + HostApiClient.API_BASE, e);
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

        copyBooleanIfPresent(payload, merged, TRAINER_HUD_ACTIVE);
        copyStringIfPresent(payload, merged, TRAINER_HUD_TEXT, "");
        copyValueIfPresent(payload, merged, TRAINER_HUD_JSON);
        copyStringIfPresent(payload, merged, CONNECTION_MODE, "live");
        return merged;
    }

    private static void copyBooleanIfPresent(JSONObject source, JSONObject target, String key) {
        if (!source.has(key)) {
            return;
        }
        putQuietly(target, key, source.optBoolean(key, false));
    }

    private static void copyStringIfPresent(JSONObject source, JSONObject target, String key, String fallback) {
        if (!source.has(key)) {
            return;
        }
        putQuietly(target, key, source.optString(key, fallback));
    }

    private static void copyValueIfPresent(JSONObject source, JSONObject target, String key) {
        if (!source.has(key)) {
            return;
        }
        putQuietly(target, key, source.opt(key));
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

    public static final class DaemonStatusSnapshot {
        private final boolean reachable;
        private final boolean wasReachable;
        private final String hostName;
        private final String daemonState;
        private final long runId;
        private final String lastError;
        private final boolean errorChanged;
        private final long uptimeSec;
        private final String service;
        private final String buildRevision;
        private final JSONObject metrics;

        private DaemonStatusSnapshot(Builder builder) {
            this.reachable = builder.reachable;
            this.wasReachable = builder.wasReachable;
            this.hostName = builder.hostName;
            this.daemonState = builder.daemonState;
            this.runId = builder.runId;
            this.lastError = builder.lastError;
            this.errorChanged = builder.errorChanged;
            this.uptimeSec = builder.uptimeSec;
            this.service = builder.service;
            this.buildRevision = builder.buildRevision;
            this.metrics = builder.metrics;
        }

        public boolean isReachable() {
            return reachable;
        }

        public boolean wasReachable() {
            return wasReachable;
        }

        public String getHostName() {
            return hostName;
        }

        public String getDaemonState() {
            return daemonState;
        }

        public long getRunId() {
            return runId;
        }

        public String getLastError() {
            return lastError;
        }

        public boolean isErrorChanged() {
            return errorChanged;
        }

        public long getUptimeSec() {
            return uptimeSec;
        }

        public String getService() {
            return service;
        }

        public String getBuildRevision() {
            return buildRevision;
        }

        public JSONObject getMetrics() {
            return metrics;
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private boolean reachable;
            private boolean wasReachable;
            private String hostName = "-";
            private String daemonState = "IDLE";
            private long runId;
            private String lastError = "";
            private boolean errorChanged;
            private long uptimeSec;
            private String service = "-";
            private String buildRevision = "-";
            private JSONObject metrics = new JSONObject();

            private Builder() {}

            public Builder reachable(boolean reachable) {
                this.reachable = reachable;
                return this;
            }

            public Builder wasReachable(boolean wasReachable) {
                this.wasReachable = wasReachable;
                return this;
            }

            public Builder hostName(String hostName) {
                this.hostName = hostName;
                return this;
            }

            public Builder daemonState(String daemonState) {
                this.daemonState = daemonState;
                return this;
            }

            public Builder runId(long runId) {
                this.runId = runId;
                return this;
            }

            public Builder lastError(String lastError) {
                this.lastError = lastError;
                return this;
            }

            public Builder errorChanged(boolean errorChanged) {
                this.errorChanged = errorChanged;
                return this;
            }

            public Builder uptimeSec(long uptimeSec) {
                this.uptimeSec = uptimeSec;
                return this;
            }

            public Builder service(String service) {
                this.service = service;
                return this;
            }

            public Builder buildRevision(String buildRevision) {
                this.buildRevision = buildRevision;
                return this;
            }

            public Builder metrics(JSONObject metrics) {
                this.metrics = metrics != null ? metrics : new JSONObject();
                return this;
            }

            public DaemonStatusSnapshot build() {
                return new DaemonStatusSnapshot(this);
            }
        }
    }
}
