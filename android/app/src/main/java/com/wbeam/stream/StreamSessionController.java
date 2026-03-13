package com.wbeam.stream;

import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import com.wbeam.api.HostApiClient;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;

/**
 * Manages host stream session lifecycle: start, stop, apply-config.
 * All API calls are dispatched on the ioExecutor; results are delivered
 * back to the UI thread via the Callbacks interface.
 */
public final class StreamSessionController {

    @SuppressWarnings("java:S1068")
    private static final String TAG = "WBeamSessionCtrl";
    private static final String STATE_CONNECTING = "connecting";

    private static final long AUTO_START_FAILURE_BACKOFF_MS = 30_000L;
    private static final long STOP_SUPPRESS_AUTO_START_MS   = 12_000L;

    private final Handler         uiHandler;
    private final ExecutorService ioExecutor;
    private final Callbacks       callbacks;

    /** Callback interface — implemented by MainActivity. */
    public interface Callbacks {
        /** Current UI state: update status bar. */
        void onStatus(String state, String info, long bps);

        /** Raw daemon status JSON received — forward to StatusPoller for state update. */
        void onDaemonStatusJson(JSONObject status);

        /** Start the H264TcpPlayer if surface is ready and player not running. */
        void ensureDecoderRunning();

        /** Stop the live view (player + media player). */
        void stopLiveView();

        /** Show a toast. */
        void showToast(String msg, boolean longToast);

        /** Log a warning line to the live log. */
        void appendLiveLogWarn(String msg);

        /** Log an error line to the live log and update status. */
        void handleApiFailure(String prefix, boolean userAction, Exception e);

        /** Bundle the current settings into a JSON config payload for /apply. */
        JSONObject buildConfigPayload();

        /** Suppress auto-start for a given duration (ms) via StatusPoller. */
        void suppressAutoStart(long durationMs);

        /** Record that a start attempt was made (for cooldown). */
        void recordAutoStartAttempt();

        /** Mark auto-start as pending (waiting for STREAMING) or not. */
        void setAutoStartPending(boolean pending);
    }

    public StreamSessionController(
            Handler uiHandler,
            ExecutorService ioExecutor,
            Callbacks callbacks
    ) {
        this.uiHandler  = uiHandler;
        this.ioExecutor = ioExecutor;
        this.callbacks  = callbacks;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Request the host daemon to start streaming.
     *
     * @param userAction   true if triggered by the user pressing Start Live
     * @param ensureViewer true if H264TcpPlayer should be started after host confirms
     */
    public void requestStart(boolean userAction, boolean ensureViewer) {
        callbacks.suppressAutoStart(0);          // clear suppression
        callbacks.setAutoStartPending(!userAction);
        callbacks.recordAutoStartAttempt();
        callbacks.onStatus(STATE_CONNECTING, "requesting host start", 0);

        JSONObject cfg = callbacks.buildConfigPayload();
        ioExecutor.execute(() -> {
            try {
                HostApiClient.apiRequestWithRetry(
                        "POST", "/apply", cfg, HostApiClient.API_RETRY_ATTEMPTS);
                JSONObject status = HostApiClient.apiRequestWithRetry(
                        "POST", "/start", new JSONObject(), HostApiClient.API_RETRY_ATTEMPTS);
                uiHandler.post(() -> {
                    callbacks.onDaemonStatusJson(status);
                    if (ensureViewer) {
                        callbacks.ensureDecoderRunning();
                    }
                    if (userAction) {
                        callbacks.showToast("Host stream start requested", false);
                    }
                });
            } catch (Exception e) {
                uiHandler.post(() -> {
                    callbacks.setAutoStartPending(false);
                    if (!userAction) {
                        callbacks.suppressAutoStart(AUTO_START_FAILURE_BACKOFF_MS);
                    }
                    callbacks.handleApiFailure("start failed", userAction, e);
                });
            }
        });
    }

    /**
     * Request the host daemon to stop streaming.
     */
    public void requestStop(boolean userAction) {
        callbacks.suppressAutoStart(STOP_SUPPRESS_AUTO_START_MS);
        callbacks.setAutoStartPending(false);

        ioExecutor.execute(() -> {
            try {
                JSONObject status = HostApiClient.apiRequestWithRetry(
                        "POST", "/stop", new JSONObject(), HostApiClient.API_RETRY_ATTEMPTS);
                uiHandler.post(() -> {
                    callbacks.stopLiveView();
                    callbacks.onDaemonStatusJson(status);
                    callbacks.onStatus("idle", "stream stopped", 0);
                    if (userAction) {
                        callbacks.showToast("Host stream stopped", false);
                    }
                });
            } catch (Exception e) {
                uiHandler.post(() -> callbacks.handleApiFailure("stop failed", userAction, e));
            }
        });
    }

    /**
     * POST a settings payload to an arbitrary API path, then refresh daemon status.
     */
    public void postApiCommand(
            String method,
            String path,
            JSONObject payload,
            String successInfo,
            boolean toastOnSuccess,
            boolean ensureViewer
    ) {
        callbacks.onStatus(STATE_CONNECTING, "sending " + path, 0);
        ioExecutor.execute(() -> {
            try {
                JSONObject response = HostApiClient.apiRequestWithRetry(
                        method, path, payload, HostApiClient.API_RETRY_ATTEMPTS);
                uiHandler.post(() -> {
                    callbacks.onDaemonStatusJson(response);
                    callbacks.onStatus(STATE_CONNECTING, successInfo, 0);
                    if (ensureViewer) {
                        callbacks.ensureDecoderRunning();
                    }
                    if (toastOnSuccess) {
                        callbacks.showToast("Applied on host", false);
                    }
                });
            } catch (Exception e) {
                uiHandler.post(() -> callbacks.handleApiFailure("api failed", toastOnSuccess, e));
            }
        });
    }
}
