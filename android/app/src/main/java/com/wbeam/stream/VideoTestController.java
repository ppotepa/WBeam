package com.wbeam.stream;

import android.media.MediaPlayer;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import com.wbeam.api.HostApiClient;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Handles public video latency tests and USB bandwidth tests.
 * Owns the MediaPlayer instance and live-test overlay state.
 * All UI interactions go through the Callbacks interface.
 */
public final class VideoTestController {

    private static final String TAG = "WBeamVideoTest";
    private static final String RUN_TESTS_LOG_PREFIX = "[RUN TESTS LIVE] ";
    private static final String STATUS_STREAMING = "streaming";
    private static final long   LIVE_TEST_START_TIMEOUT_MS = 12_000L;
    private static final int    BANDWIDTH_TEST_MB          = 64;
    private static final String TEST_VIDEO_URL =
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4";

    private final Handler         uiHandler;
    private final ExecutorService ioExecutor;
    private final Callbacks       callbacks;
    private final Runnable        startTimeoutTask;

    private MediaPlayer mediaPlayer;

    // Overlay state — read back by MainActivity.updatePreflightOverlay() via getters.
    private boolean overlayActive = false;
    private String  overlayTitle  = "";
    private String  overlayBody   = "";
    private String  overlayHint   = "";

    // ── Callback interface ────────────────────────────────────────────────────

    /** Implemented by MainActivity to allow the controller to drive UI. */
    public interface Callbacks {
        /** Current preview surface (may be null or invalid). */
        Surface getSurface();
        /** Stop the H264TcpPlayer if running. */
        void stopVideoPlayer();
        /** Current daemon state string (STREAMING / IDLE / ERROR …). */
        String getDaemonState();
        /** Whether daemon control API is reachable. */
        boolean isDaemonReachable();
        /** Update the status bar. */
        void onStatus(String state, String info, long bps);
        /** Update the stats line. */
        void onStatsLine(String line);
        /** Append an info, warning or error line to the live log. */
        void logInfo(String msg);
        void logWarn(String msg);
        void logError(String msg);
        /** Called when overlay state changed — MainActivity re-renders. */
        void onOverlayChanged();
        /** Make the live log visible. */
        void setLiveLogVisible(boolean visible);
        /** Show a toast message. */
        void showToast(String msg, boolean longToast);
        /** Snapshot current settings for the preset record. */
        TestConfig getTestConfig();
    }

    // ── Data types ────────────────────────────────────────────────────────────

    /** Snapshot of settings captured when a live test starts. */
    public static final class TestConfig {
        public final String profile;
        public final String encoder;
        public final String cursorMode;
        public final int    width;
        public final int    height;
        public final int    fps;
        public final int    bitrateMbps;

        public TestConfig(String profile, String encoder, String cursorMode,
                          int width, int height, int fps, int bitrateMbps) {
            this.profile    = profile;
            this.encoder    = encoder;
            this.cursorMode = cursorMode;
            this.width      = width;
            this.height     = height;
            this.fps        = fps;
            this.bitrateMbps = bitrateMbps;
        }

        public String toLine() {
            return profile + " " + width + "x" + height + " " + fps + "fps "
                    + bitrateMbps + "Mbps " + encoder + " cursor=" + cursorMode;
        }

        public String toMultiline() {
            return "profile=" + profile
                    + "\nsize=" + width + "x" + height
                    + "\nfps=" + fps
                    + "\nbitrate=" + bitrateMbps + "Mbps"
                    + "\nencoder=" + encoder
                    + "\ncursor=" + cursorMode;
        }
    }

    private static final class BandwidthResult {
        final long   totalBytes;
        final long   bps;
        final double mbps;
        final double mibPerSec;
        final double seconds;
        final int    totalMiB;

        private BandwidthResult(long totalBytes, long bps, double mbps,
                                double mibPerSec, double seconds, int totalMiB) {
            this.totalBytes = totalBytes;
            this.bps        = bps;
            this.mbps       = mbps;
            this.mibPerSec  = mibPerSec;
            this.seconds    = seconds;
            this.totalMiB   = totalMiB;
        }

        static BandwidthResult from(long totalBytes, long durationNs, int requestedMiB) {
            double sec = Math.max(0.001, durationNs / 1_000_000_000.0);
            long   bps = (long) ((totalBytes * 8.0) / sec);
            return new BandwidthResult(
                    totalBytes, bps,
                    bps / 1_000_000.0,
                    (totalBytes / 1024.0 / 1024.0) / sec,
                    sec,
                    requestedMiB
            );
        }
    }

    // ── Constructor ───────────────────────────────────────────────────────────

    public VideoTestController(Handler uiHandler, ExecutorService ioExecutor, Callbacks callbacks) {
        this.uiHandler  = uiHandler;
        this.ioExecutor = ioExecutor;
        this.callbacks  = callbacks;
        this.startTimeoutTask = () -> {
            logError("timed out waiting for media to prepare, aborting");
            callbacks.onStatus("error", "RUN TESTS LIVE timeout", 0);
            setOverlay("RUN TESTS LIVE TIMEOUT", null,
                    "media never became ready \u2013 network too slow?");
            release();
        };
    }

    // ── Overlay state getters (read by MainActivity.updatePreflightOverlay) ───

    public boolean isOverlayActive() { return overlayActive; }
    public String  getOverlayTitle()  { return overlayTitle;  }
    public String  getOverlayBody()   { return overlayBody;   }
    public String  getOverlayHint()   { return overlayHint;   }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Stream a public test video over the preview surface to validate the full
     * decode/render pipeline through the local WiFi/USB link.
     */
    public void startPublicVideoTest() {
        Surface surface = callbacks.getSurface();
        if (surface == null || !surface.isValid()) {
            callbacks.onStatus("error", "surface not ready yet", 0);
            return;
        }

        TestConfig cfg = callbacks.getTestConfig();
        String presetLine = cfg.toLine();
        logInfo("requested with preset: " + presetLine);

        callbacks.setLiveLogVisible(true);
        setOverlay("RUN TESTS LIVE LOADING",
                "preset\n" + cfg.toMultiline(),
                "phase: preparing media pipeline");
        callbacks.stopVideoPlayer();
        release();

        try {
            callbacks.onStatus("connecting", "RUN TESTS LIVE loading", 0);
            callbacks.onStatsLine("source: RUN TESTS LIVE | " + presetLine);

            MediaPlayer mp = new MediaPlayer();
            mediaPlayer = mp;
            mp.setSurface(surface);
            mp.setDataSource(TEST_VIDEO_URL);
            mp.setLooping(true);
            mp.setVolume(0f, 0f);

            uiHandler.removeCallbacks(startTimeoutTask);
            uiHandler.postDelayed(startTimeoutTask, LIVE_TEST_START_TIMEOUT_MS);

            mp.setOnPreparedListener(ready -> {
                if (mediaPlayer != ready) {
                    logWarn("prepared callback ignored for stale player");
                    return;
                }
                uiHandler.removeCallbacks(startTimeoutTask);
                logInfo("media prepared; starting playback");
                try {
                    ready.start();
                } catch (IllegalStateException ex) {
                    logError("start() failed: " + shortError(ex));
                    callbacks.onStatus("error",
                            "RUN TESTS LIVE failed: IllegalStateException", 0);
                    setOverlay("RUN TESTS LIVE FAILED",
                            "preset\n" + cfg.toMultiline(), shortError(ex));
                    release();
                    return;
                }
                callbacks.onStatus(STATUS_STREAMING, "RUN TESTS LIVE playing", 0);
                setOverlay("RUN TESTS LIVE ACTIVE",
                        "preset\n" + cfg.toMultiline(), "phase: playback started");
                uiHandler.postDelayed(this::clearOverlay, 900);
            });

            mp.setOnCompletionListener(done -> {
                if (mediaPlayer != done) return;
                uiHandler.removeCallbacks(startTimeoutTask);
                logInfo("playback completed");
                callbacks.onStatus("idle", "RUN TESTS LIVE completed", 0);
                clearOverlay();
            });

            mp.setOnErrorListener((errPlayer, what, extra) -> {
                if (mediaPlayer != errPlayer) return true;
                uiHandler.removeCallbacks(startTimeoutTask);
                logError("player error what=" + what + " extra=" + extra);
                callbacks.onStatus("error",
                        "RUN TESTS LIVE error: " + what + "/" + extra, 0);
                setOverlay("RUN TESTS LIVE ERROR",
                        "preset\n" + cfg.toMultiline(),
                        "player error: " + what + "/" + extra);
                return true;
            });

            mp.setOnInfoListener((infoPlayer, what, extra) -> {
                if (mediaPlayer != infoPlayer) return true;
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    logWarn("buffering start");
                    callbacks.onStatus("connecting", "RUN TESTS LIVE buffering", 0);
                    setOverlay("RUN TESTS LIVE LOADING",
                            "preset\n" + cfg.toMultiline(), "phase: buffering");
                } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    logInfo("buffering end");
                    callbacks.onStatus(STATUS_STREAMING, "RUN TESTS LIVE playing", 0);
                    setOverlay("RUN TESTS LIVE ACTIVE",
                            "preset\n" + cfg.toMultiline(), "phase: streaming frames");
                }
                return false;
            });

            logInfo("prepareAsync() start; source=public test stream");
            mp.prepareAsync();

        } catch (Exception e) {
            uiHandler.removeCallbacks(startTimeoutTask);
            logError("startup failed: " + shortError(e));
            Log.e(TAG, "failed to start public test video", e);
            callbacks.onStatus("error",
                    "RUN TESTS LIVE failed: " + e.getClass().getSimpleName(), 0);
            setOverlay("RUN TESTS LIVE FAILED",
                    "preset\n" + callbacks.getTestConfig().toMultiline(), shortError(e));
            release();
        }
    }

    /**
     * Download a fixed-size payload from the host speed-test endpoint and
     * report the measured USB throughput.
     */
    public void startBandwidthTest() {
        if (!callbacks.isDaemonReachable()) {
            callbacks.onStatus("error",
                    "host API offline - cannot run bandwidth test", 0);
            callbacks.showToast("Host API offline", false);
            return;
        }

        callbacks.onStatus("connecting", "running USB bandwidth test...", 0);
        callbacks.onStatsLine(
                "bandwidth test: downloading random payload from host API");
        callbacks.logInfo(
                "bandwidth test start: /v1/speedtest?mb=" + BANDWIDTH_TEST_MB);

        ioExecutor.execute(() -> {
            try {
                BandwidthResult result = runBandwidthTest(BANDWIDTH_TEST_MB);
                uiHandler.post(() -> {
                    String uiState = uiStateFromDaemonState(callbacks.getDaemonState());
                    String summary = String.format(Locale.US,
                            "bandwidth %.1f Mbps (%.2f MiB/s), %d MiB in %.2fs",
                            result.mbps, result.mibPerSec,
                            result.totalMiB, result.seconds);
                    callbacks.onStatus(uiState, summary, result.bps);
                    callbacks.onStatsLine(String.format(Locale.US,
                            "bandwidth test: %.1f Mbps | %.2f MiB/s | bytes=%d | sec=%.2f",
                            result.mbps, result.mibPerSec,
                            result.totalBytes, result.seconds));
                    callbacks.logInfo(summary);
                    callbacks.showToast(
                            String.format(Locale.US,
                                    "Bandwidth: %.1f Mbps", result.mbps), false);
                });
            } catch (Exception e) {
                uiHandler.post(() -> {
                    String reason = shortError(e);
                    callbacks.onStatus("error",
                            "bandwidth test failed: " + reason, 0);
                    callbacks.logError("bandwidth test failed: " + reason);
                    callbacks.showToast(
                            "Bandwidth test failed: " + reason, true);
                });
            }
        });
    }

    /**
     * Release MediaPlayer and cancel any pending start timer.
     * Safe to call multiple times.
     */
    public void release() {
        uiHandler.removeCallbacks(startTimeoutTask);
        MediaPlayer mp = mediaPlayer;
        mediaPlayer = null;
        if (mp != null) {
            try { mp.setOnPreparedListener(null);   } catch (Exception ignored) { }
            try { mp.setOnCompletionListener(null); } catch (Exception ignored) { }
            try { mp.setOnErrorListener(null);      } catch (Exception ignored) { }
            try { mp.setOnInfoListener(null);       } catch (Exception ignored) { }
            try { mp.stop();    } catch (Exception ignored) { }
            try { mp.reset();   } catch (Exception ignored) { }
            try { mp.release(); } catch (Exception ignored) { }
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private BandwidthResult runBandwidthTest(int sizeMb) throws IOException {
        Request request = new Request.Builder()
                .url(HostApiClient.API_BASE + "/v1/speedtest?mb=" + sizeMb)
                .header("Accept", "application/octet-stream")
                .get()
                .build();

        try (Response response = HostApiClient.SPEEDTEST_HTTP.newCall(request).execute()) {
            int code = response.code();
            if (code < 200 || code >= 300) {
                ResponseBody errBody = response.body();
                String msg = "HTTP " + code;
                if (errBody != null) {
                    String errText = errBody.string();
                    if (errText != null && !errText.isEmpty()) msg += ": " + errText;
                }
                throw new IOException(msg);
            }
            ResponseBody body = response.body();
            if (body == null) throw new IOException("empty response");

            long totalBytes = 0;
            byte[] buf = new byte[64 * 1024];
            try (BufferedInputStream in =
                         new BufferedInputStream(body.byteStream(), 256 * 1024)) {
                long startNs = SystemClock.elapsedRealtimeNanos();
                int  read;
                while ((read = in.read(buf)) >= 0) {
                    if (read > 0) totalBytes += read;
                }
                long durationNs = Math.max(1L,
                        SystemClock.elapsedRealtimeNanos() - startNs);
                return BandwidthResult.from(totalBytes, durationNs, sizeMb);
            }
        }
    }

    private static String uiStateFromDaemonState(String daemonState) {
        if ("STREAMING".equals(daemonState))                            return STATUS_STREAMING;
        if ("STARTING".equals(daemonState)
                || "RECONNECTING".equals(daemonState))                  return "connecting";
        if ("ERROR".equals(daemonState)
                || "DISCONNECTED".equals(daemonState))                  return "error";
        return "idle";
    }

    private void setOverlay(String title, String body, String hint) {
        overlayActive = true;
        overlayTitle  = title != null ? title : "RUN TESTS LIVE";
        overlayBody   = body  != null ? body  : "";
        overlayHint   = hint  != null ? hint  : "";
        callbacks.onOverlayChanged();
    }

    private void clearOverlay() {
        overlayActive = false;
        overlayTitle = overlayBody = overlayHint = "";
        callbacks.onOverlayChanged();
    }

    private void logInfo(String msg)  {
        callbacks.logInfo(RUN_TESTS_LOG_PREFIX + msg);
        Log.i(TAG, RUN_TESTS_LOG_PREFIX + msg);
    }
    private void logWarn(String msg)  {
        callbacks.logWarn(RUN_TESTS_LOG_PREFIX + msg);
        Log.w(TAG, RUN_TESTS_LOG_PREFIX + msg);
    }
    private void logError(String msg) {
        callbacks.logError(RUN_TESTS_LOG_PREFIX + msg);
        Log.e(TAG, RUN_TESTS_LOG_PREFIX + msg);
    }

    private static String shortError(Exception e) {
        String msg = e.getMessage();
        if (msg != null) {
            msg = msg.trim();
            if (!msg.isEmpty()) return msg.length() > 120 ? msg.substring(0, 120) : msg;
        }
        return e.getClass().getSimpleName();
    }
}
