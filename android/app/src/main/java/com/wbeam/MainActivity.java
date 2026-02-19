package com.wbeam;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "WBeamMain";
    private static final String STATE_IDLE = "idle";
    private static final String STATE_CONNECTING = "connecting";
    private static final String STATE_STREAMING = "streaming";
    private static final String STATE_ERROR = "error";

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5000;
    private static final int CONTROL_PORT = 5001;
    private static final String API_BASE = "http://" + HOST + ":" + CONTROL_PORT;
    private static final long STATUS_POLL_MS = 1200;
    private static final long AUTO_START_COOLDOWN_MS = 4000;
    private static final long STOP_SUPPRESS_AUTO_START_MS = 12000;
    private static final int API_RETRY_ATTEMPTS = 2;
    private static final long CLIENT_METRICS_INTERVAL_MS = 900;
    private static final long HUD_ADB_LOG_INTERVAL_MS = 1000;
    private static final int TRANSPORT_QUEUE_MAX_FRAMES = 3;
    private static final int DECODE_QUEUE_MAX_FRAMES = 2;
    private static final int RENDER_QUEUE_MAX_FRAMES = 1;
    private static final String TEST_VIDEO_URL =
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4";

    private static final String[] PROFILE_OPTIONS = {"lowlatency", "balanced", "ultra"};
    private static final String[] ENCODER_OPTIONS = {"auto", "nvenc", "openh264"};
    private static final String[] CURSOR_OPTIONS = {"hidden", "embedded", "metadata"};

    private static final String PREFS = "wbeam_settings";
    private static final String PREF_PROFILE = "profile";
    private static final String PREF_ENCODER = "encoder";
    private static final String PREF_CURSOR = "cursor";
    private static final String PREF_RES_SCALE = "res_scale";
    private static final String PREF_FPS = "fps";
    private static final String PREF_BITRATE = "bitrate";
    private static final String PREF_LOCAL_CURSOR = "local_cursor_overlay";
    private static final int LIVE_LOG_MAX_LINES = 80;

    private View rootLayout;
    private View topBar;
    private View settingsPanel;
    private View statusPanel;
    private View perfHudPanel;
    private View preflightOverlay;
    private View debugControlsRow;
    private View statusLed;
    private View cursorOverlay;
    private TextView statusText;
    private TextView detailText;
    private TextView bpsText;
    private TextView statsText;
    private TextView perfHudText;
    private TextView preflightTitleText;
    private TextView preflightBodyText;
    private TextView preflightHintText;
    private TextView liveLogText;
    private TextView resValueText;
    private TextView fpsValueText;
    private TextView bitrateValueText;
    private TextView hostHintText;

    private Spinner profileSpinner;
    private Spinner encoderSpinner;
    private Spinner cursorSpinner;
    private SeekBar resolutionSeek;
    private SeekBar fpsSeek;
    private SeekBar bitrateSeek;

    private Button settingsButton;
    private Button logButton;
    private Button settingsCloseButton;
    private Button applySettingsButton;
    private Button startButton;
    private Button stopButton;
    private Button testButton;
    private Button fullscreenButton;
    private Button cursorOverlayButton;

    private boolean settingsVisible = false;
    private boolean isFullscreen = false;
    private boolean cursorOverlayEnabled = true;
    private boolean debugControlsVisible = false;
    private boolean liveLogVisible = false;
    private boolean daemonReachable = false;
    private String daemonHostName = "-";
    private String daemonService = "-";
    private String daemonState = "IDLE";
    private long daemonRunId = 0L;
    private long daemonUptimeSec = 0L;
    private boolean statusPollInFlight = false;
    private long lastAutoStartAt = 0L;
    private long suppressAutoStartUntil = 0L;
    private long lastClientMetricsPostAt = 0L;
    private long lastHudAdbLogAt = 0L;
    private String lastHudAdbSnapshot = "";
    private boolean surfaceReady = false;
    private boolean preflightComplete = false;
    private int preflightAnimTick = 0;
    private boolean hwAvcDecodeAvailable = false;
    private String lastUiState = STATE_IDLE;
    private String lastUiInfo = "tap Settings -> Start Live";
    private long lastUiBps = 0;
    private final SpannableStringBuilder liveLogBuffer = new SpannableStringBuilder();

    private Surface surface;
    private H264TcpPlayer player;
    private MediaPlayer mediaPlayer;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();
    private final Runnable statusPollTask = new Runnable() {
        @Override
        public void run() {
            pollDaemonStatusAsync();
            uiHandler.postDelayed(this, STATUS_POLL_MS);
        }
    };
    private final Runnable preflightPulseTask = new Runnable() {
        @Override
        public void run() {
            preflightAnimTick = (preflightAnimTick + 1) % 4;
            updatePreflightOverlay();
            uiHandler.postDelayed(this, 350);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupSpinners();
        setupSeekbars();
        setupSurfaceCallbacks();
        setupButtons();
        loadSavedSettings();
        hwAvcDecodeAvailable = hasHardwareAvcDecoder();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    1001
            );
        }

        applySettings(false);
        setDebugControlsVisible(false);
        setFullscreen(false);
        updateStatsLine("fps in/out: - | drops: - | late: - | q(t/d/r): -/-/- | reconnects: -");
        updatePerfHudUnavailable();
        startPreflightPulse();
        updatePreflightOverlay();
        updateStatus(STATE_IDLE, "tap Settings -> Start Live", 0);
        startStatusPolling();
    }

    @Override
    protected void onDestroy() {
        stopStatusPolling();
        stopPreflightPulse();
        stopLiveView();
        releaseMediaPlayer();
        ioExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (settingsVisible) {
            hideSettingsPanel();
            return;
        }
        if (isFullscreen) {
            setFullscreen(false);
            return;
        }
        super.onBackPressed();
    }

    private void bindViews() {
        rootLayout = findViewById(R.id.rootLayout);
        topBar = findViewById(R.id.topBar);
        settingsPanel = findViewById(R.id.settingsPanel);
        statusPanel = findViewById(R.id.statusPanel);
        perfHudPanel = findViewById(R.id.perfHudPanel);
        preflightOverlay = findViewById(R.id.preflightOverlay);
        debugControlsRow = findViewById(R.id.debugControlsRow);
        statusLed = findViewById(R.id.statusLed);
        cursorOverlay = findViewById(R.id.cursorOverlay);

        statusText = findViewById(R.id.statusText);
        detailText = findViewById(R.id.detailText);
        bpsText = findViewById(R.id.bpsText);
        statsText = findViewById(R.id.statsText);
        perfHudText = findViewById(R.id.perfHudText);
        preflightTitleText = findViewById(R.id.preflightTitle);
        preflightBodyText = findViewById(R.id.preflightBody);
        preflightHintText = findViewById(R.id.preflightHint);
        liveLogText = findViewById(R.id.liveLogText);

        resValueText = findViewById(R.id.resValueText);
        fpsValueText = findViewById(R.id.fpsValueText);
        bitrateValueText = findViewById(R.id.bitrateValueText);
        hostHintText = findViewById(R.id.hostHintText);

        profileSpinner = findViewById(R.id.profileSpinner);
        encoderSpinner = findViewById(R.id.encoderSpinner);
        cursorSpinner = findViewById(R.id.cursorSpinner);

        resolutionSeek = findViewById(R.id.resolutionSeek);
        fpsSeek = findViewById(R.id.fpsSeek);
        bitrateSeek = findViewById(R.id.bitrateSeek);

        settingsButton = findViewById(R.id.settingsButton);
        logButton = findViewById(R.id.logButton);
        settingsCloseButton = findViewById(R.id.settingsCloseButton);
        applySettingsButton = findViewById(R.id.applySettingsButton);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
        testButton = findViewById(R.id.testButton);
        fullscreenButton = findViewById(R.id.fullscreenButton);
        cursorOverlayButton = findViewById(R.id.cursorOverlayButton);
    }

    private void setupSpinners() {
        profileSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, PROFILE_OPTIONS));
        encoderSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ENCODER_OPTIONS));
        cursorSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, CURSOR_OPTIONS));
    }

    private void setupSeekbars() {
        resolutionSeek.setMax(50); // 50..100%
        fpsSeek.setMax(72); // 24..96 fps
        bitrateSeek.setMax(55); // 5..60 Mbps

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateSettingValueLabels();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };

        resolutionSeek.setOnSeekBarChangeListener(listener);
        fpsSeek.setOnSeekBarChangeListener(listener);
        bitrateSeek.setOnSeekBarChangeListener(listener);
    }

    private void setupSurfaceCallbacks() {
        SurfaceView preview = findViewById(R.id.previewSurface);
        preview.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                surface = holder.getSurface();
                surfaceReady = surface != null && surface.isValid();
                updatePreflightOverlay();
                updateStatus(STATE_IDLE, "surface ready", 0);
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                surface = holder.getSurface();
                surfaceReady = surface != null && surface.isValid();
                updatePreflightOverlay();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                stopLiveView();
                surface = null;
                surfaceReady = false;
                preflightComplete = false;
                updatePreflightOverlay();
                hideCursorOverlay();
                updateStatus(STATE_IDLE, "surface destroyed", 0);
            }
        });

        preview.setOnTouchListener((v, event) -> {
            if (!cursorOverlayEnabled) {
                return false;
            }
            updateCursorOverlay(event.getX(), event.getY(), event.getActionMasked());
            return false;
        });

        preview.setOnGenericMotionListener((v, event) -> {
            if (!cursorOverlayEnabled) {
                return false;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE ||
                    event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                updateCursorOverlay(event.getX(), event.getY(), event.getActionMasked());
            }
            return false;
        });
    }

    private void setupButtons() {
        settingsButton.setOnClickListener(v -> toggleSettingsPanel());
        logButton.setOnClickListener(v -> toggleLiveLogPanel());
        settingsButton.setOnLongClickListener(v -> {
            setDebugControlsVisible(!debugControlsVisible);
            Toast.makeText(
                    this,
                    debugControlsVisible ? "Debug controls ON" : "Debug controls OFF",
                    Toast.LENGTH_SHORT
            ).show();
            return true;
        });
        settingsCloseButton.setOnClickListener(v -> hideSettingsPanel());
        applySettingsButton.setOnClickListener(v -> applySettings(true));

        startButton.setOnClickListener(v -> requestHostStart(true, true));
        stopButton.setOnClickListener(v -> requestHostStop(true));
        testButton.setOnClickListener(v -> startPublicVideoTest());

        fullscreenButton.setOnClickListener(v -> toggleFullscreen());
        cursorOverlayButton.setOnClickListener(v -> toggleCursorOverlayMode());
    }

    private void setDebugControlsVisible(boolean visible) {
        debugControlsVisible = visible;
        int visibility = visible ? View.VISIBLE : View.GONE;
        if (debugControlsRow != null) {
            debugControlsRow.setVisibility(visibility);
        }
        if (testButton != null) {
            testButton.setVisibility(visibility);
        }
    }

    private void toggleLiveLogPanel() {
        liveLogVisible = !liveLogVisible;
        if (liveLogText != null) {
            liveLogText.setVisibility(liveLogVisible ? View.VISIBLE : View.GONE);
        }
        if (logButton != null) {
            logButton.setText(liveLogVisible ? "Log ON" : "Log");
        }
    }

    private void loadSavedSettings() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        String profile = prefs.getString(PREF_PROFILE, "balanced");
        String encoder = prefs.getString(PREF_ENCODER, "auto");
        String cursor = prefs.getString(PREF_CURSOR, "hidden");

        setSpinnerSelection(profileSpinner, PROFILE_OPTIONS, profile);
        setSpinnerSelection(encoderSpinner, ENCODER_OPTIONS, encoder);
        setSpinnerSelection(cursorSpinner, CURSOR_OPTIONS, cursor);

        int scale = prefs.getInt(PREF_RES_SCALE, 100);
        int fps = prefs.getInt(PREF_FPS, 60);
        int bitrate = prefs.getInt(PREF_BITRATE, 25);

        resolutionSeek.setProgress(clamp(scale, 50, 100) - 50);
        fpsSeek.setProgress(clamp(fps, 24, 96) - 24);
        bitrateSeek.setProgress(clamp(bitrate, 5, 60) - 5);

        cursorOverlayEnabled = prefs.getBoolean(PREF_LOCAL_CURSOR, true);
        cursorOverlayButton.setText(cursorOverlayEnabled ? "Local Cursor Overlay ON" : "Local Cursor Overlay OFF");

        updateSettingValueLabels();
    }

    private void saveSettings() {
        SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
        e.putString(PREF_PROFILE, getSelectedProfile());
        e.putString(PREF_ENCODER, getSelectedEncoder());
        e.putString(PREF_CURSOR, getSelectedCursorMode());
        e.putInt(PREF_RES_SCALE, getResolutionScalePercent());
        e.putInt(PREF_FPS, getSelectedFps());
        e.putInt(PREF_BITRATE, getSelectedBitrateMbps());
        e.putBoolean(PREF_LOCAL_CURSOR, cursorOverlayEnabled);
        e.apply();
    }

    private void applySettings(boolean userAction) {
        updateSettingValueLabels();
        saveSettings();
        updateHostHint();

        if (userAction) {
            suppressAutoStartUntil = 0;
            JSONObject payload = buildConfigPayload();
            postApiCommand(
                    "POST",
                    "/apply",
                    payload,
                    "applied",
                    true,
                    false
            );
        }
    }

    private void updateSettingValueLabels() {
        int scale = getResolutionScalePercent();
        int fps = getSelectedFps();
        int bitrate = getSelectedBitrateMbps();
        int[] sz = computeScaledSize();

        resValueText.setText(scale + "% (" + sz[0] + "x" + sz[1] + ")");
        fpsValueText.setText(String.valueOf(fps));
        bitrateValueText.setText(bitrate + " Mbps");
    }

    private void updateHostHint() {
        int[] sz = computeScaledSize();
        String line1 = "Control API " + (daemonReachable ? "connected" : "waiting")
                + ": 127.0.0.1:" + CONTROL_PORT;
        String line2 = "Host: " + daemonHostName + " | Daemon: " + daemonState + " (" + daemonService + ")";
        String line3 = "Outgoing config: " + getSelectedProfile()
                + ", " + sz[0] + "x" + sz[1]
                + ", " + getSelectedFps() + "fps, "
                + getSelectedBitrateMbps() + "Mbps, "
                + getSelectedEncoder() + ", cursor " + getSelectedCursorMode();
        hostHintText.setText(line1 + "\n" + line2 + "\n" + line3);
    }

    private void startStatusPolling() {
        uiHandler.removeCallbacks(statusPollTask);
        uiHandler.post(statusPollTask);
    }

    private void stopStatusPolling() {
        uiHandler.removeCallbacks(statusPollTask);
    }

    private void pollDaemonStatusAsync() {
        if (statusPollInFlight) {
            return;
        }
        statusPollInFlight = true;
        ioExecutor.execute(() -> {
            try {
                JSONObject status = apiRequestWithRetry("GET", "/status", null, API_RETRY_ATTEMPTS);
                JSONObject health = apiRequestWithRetry("GET", "/health", null, API_RETRY_ATTEMPTS);
                JSONObject metricsPayload = apiRequestWithRetry("GET", "/metrics", null, API_RETRY_ATTEMPTS);
                JSONObject metrics = metricsPayload.optJSONObject("metrics");
                runOnUiThread(() -> onDaemonStatus(status, health, metrics));
            } catch (Exception e) {
                runOnUiThread(() -> onDaemonOffline(e));
            } finally {
                statusPollInFlight = false;
            }
        });
    }

    private void onDaemonStatus(JSONObject status, JSONObject health, JSONObject metrics) {
        boolean wasReachable = daemonReachable;
        daemonReachable = true;
        daemonHostName = status.optString("host_name", daemonHostName);
        daemonState = status.optString("state", "IDLE").toUpperCase(Locale.US);
        daemonRunId = status.optLong("run_id", daemonRunId);
        daemonUptimeSec = status.optLong("uptime", daemonUptimeSec);
        daemonService = health != null ? health.optString("service", daemonService) : daemonService;

        if (!wasReachable) {
            Toast.makeText(this, "Connected to " + daemonHostName, Toast.LENGTH_SHORT).show();
            appendLiveLogInfo("connected to host " + daemonHostName);
        }

        updateHostHint();
        refreshStatusText();
        updatePreflightOverlay();

        if (metrics != null) {
            long frameIn = metrics.optLong("frame_in", 0);
            long frameOut = metrics.optLong("frame_out", 0);
            long drops = metrics.optLong("drops", 0);
            long reconnects = metrics.optLong("reconnects", 0);
            long bps = metrics.optLong("bitrate_actual_bps", 0);
            updateStatsLine(
                    "host in/out: " + frameIn + "/" + frameOut
                            + " | drops: " + drops
                            + " | reconnects: " + reconnects
                            + " | bitrate: " + formatBps(bps)
            );
        }
        updatePerfHud(metrics);

        if ("STREAMING".equals(daemonState)) {
            ensureDecoderRunning();
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (now < suppressAutoStartUntil) {
            return;
        }
        if (now - lastAutoStartAt < AUTO_START_COOLDOWN_MS) {
            return;
        }
        if ("IDLE".equals(daemonState)) {
            requestHostStart(false, true);
        }
    }

    private void onDaemonOffline(Exception e) {
        boolean wasReachable = daemonReachable;
        daemonReachable = false;
        daemonState = "DISCONNECTED";
        updateHostHint();
        updatePerfHudUnavailable();
        preflightComplete = false;
        updatePreflightOverlay();
        if (wasReachable) {
            updateStatus(STATE_ERROR, "Host API offline: " + shortError(e), 0);
            appendLiveLogError("daemon poll failed: " + shortError(e));
            Toast.makeText(
                    this,
                    "Host daemon offline (" + shortError(e) + "). Start host: ./host/rust/scripts/run_wbeamd_rust.sh",
                    Toast.LENGTH_LONG
            ).show();
        } else {
            refreshStatusText();
        }
        Log.e(TAG, "daemon poll failed", e);
    }

    private void requestHostStart(boolean userAction, boolean ensureViewer) {
        suppressAutoStartUntil = 0;
        lastAutoStartAt = SystemClock.elapsedRealtime();
        updateStatus(STATE_CONNECTING, "requesting host start", 0);

        JSONObject cfg = buildConfigPayload();
        ioExecutor.execute(() -> {
            try {
                apiRequestWithRetry("POST", "/apply", cfg, API_RETRY_ATTEMPTS);
                JSONObject status = apiRequestWithRetry("POST", "/start", new JSONObject(), API_RETRY_ATTEMPTS);
                runOnUiThread(() -> {
                    onDaemonStatus(status, null, null);
                    if (ensureViewer) {
                        ensureDecoderRunning();
                    }
                    if (userAction) {
                        Toast.makeText(this, "Host stream start requested", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> handleApiFailure("start failed", userAction, e));
            }
        });
    }

    private void requestHostStop(boolean userAction) {
        suppressAutoStartUntil = SystemClock.elapsedRealtime() + STOP_SUPPRESS_AUTO_START_MS;
        ioExecutor.execute(() -> {
            try {
                JSONObject status = apiRequestWithRetry("POST", "/stop", new JSONObject(), API_RETRY_ATTEMPTS);
                runOnUiThread(() -> {
                    stopLiveView();
                    onDaemonStatus(status, null, null);
                    updateStatus(STATE_IDLE, "stream stopped", 0);
                    if (userAction) {
                        Toast.makeText(this, "Host stream stopped", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> handleApiFailure("stop failed", userAction, e));
            }
        });
    }

    private void ensureDecoderRunning() {
        if (surface == null || !surface.isValid()) {
            return;
        }
        if (player != null && player.isRunning()) {
            return;
        }
        startLiveView();
    }

    private JSONObject buildConfigPayload() {
        int[] sz = computeScaledSize();
        JSONObject payload = new JSONObject();
        try {
            payload.put("profile", getSelectedProfile());
            payload.put("encoder", getSelectedEncoder());
            payload.put("cursor_mode", getSelectedCursorMode());
            payload.put("size", sz[0] + "x" + sz[1]);
            payload.put("fps", getSelectedFps());
            payload.put("bitrate_kbps", getSelectedBitrateMbps() * 1000);
            payload.put("debug_fps", 0);
        } catch (JSONException ignored) {
        }
        return payload;
    }

    private void postApiCommand(
            String method,
            String path,
            JSONObject payload,
            String successInfo,
            boolean toastOnSuccess,
            boolean ensureViewer
    ) {
        updateStatus(STATE_CONNECTING, "sending " + path, 0);
        ioExecutor.execute(() -> {
            try {
                JSONObject response = apiRequestWithRetry(method, path, payload, API_RETRY_ATTEMPTS);
                runOnUiThread(() -> {
                    onDaemonStatus(response, null, null);
                    updateStatus(STATE_CONNECTING, successInfo, 0);
                    if (ensureViewer) {
                        ensureDecoderRunning();
                    }
                    if (toastOnSuccess) {
                        Toast.makeText(this, "Applied on host", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> handleApiFailure("api failed", toastOnSuccess, e));
            }
        });
    }

    private JSONObject apiRequestWithRetry(String method, String path, JSONObject payload, int attempts)
            throws IOException, JSONException {
        IOException lastIo = null;
        for (int i = 0; i < Math.max(1, attempts); i++) {
            try {
                return apiRequest(method, path, payload);
            } catch (IOException io) {
                lastIo = io;
                if (i == attempts - 1) {
                    break;
                }
                SystemClock.sleep(250L * (i + 1));
            }
        }
        throw lastIo != null ? lastIo : new IOException("request failed");
    }

    private JSONObject apiRequest(String method, String path, JSONObject payload) throws IOException, JSONException {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(API_BASE + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setUseCaches(false);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Connection", "close");

            if (payload != null) {
                byte[] body = payload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body);
                }
            }

            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            if (stream == null) {
                throw new IOException("empty response");
            }

            byte[] buf = new byte[4096];
            StringBuilder sb = new StringBuilder();
            try (BufferedInputStream in = new BufferedInputStream(stream)) {
                int read;
                while ((read = in.read(buf)) > 0) {
                    sb.append(new String(buf, 0, read, java.nio.charset.StandardCharsets.UTF_8));
                }
            }

            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + ": " + sb);
            }

            String text = sb.toString().trim();
            return text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void handleApiFailure(String prefix, boolean userAction, Exception e) {
        String reason = shortError(e);
        updateStatus(STATE_ERROR, prefix + ": " + reason, 0);
        appendLiveLogError(prefix + ": " + reason);
        Log.e(TAG, prefix + ": " + reason, e);
        if (userAction) {
            Toast.makeText(
                    this,
                    "Host daemon error (" + reason + "). Start host: ./host/rust/scripts/run_wbeamd_rust.sh",
                    Toast.LENGTH_LONG
            ).show();
        }
    }

    private void pushClientMetricsAsync(ClientMetricsSample metrics) {
        if (metrics == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastClientMetricsPostAt < CLIENT_METRICS_INTERVAL_MS) {
            return;
        }
        lastClientMetricsPostAt = now;

        ioExecutor.execute(() -> {
            try {
                apiRequestWithRetry("POST", "/v1/client-metrics", metrics.toJson(), 1);
            } catch (Exception e) {
                appendLiveLogWarn("client-metrics post failed: " + shortError(e));
            }
        });
    }

    private String shortError(Exception e) {
        String msg = e.getMessage();
        if (msg != null) {
            msg = msg.trim();
            if (!msg.isEmpty()) {
                if (msg.length() > 120) {
                    return msg.substring(0, 120);
                }
                return msg;
            }
        }
        return e.getClass().getSimpleName();
    }

    private void startLiveView() {
        releaseMediaPlayer();
        if (surface == null || !surface.isValid()) {
            updateStatus(STATE_ERROR, "surface not ready yet", 0);
            return;
        }
        if (player != null && player.isRunning()) {
            updateStatus(STATE_STREAMING, "already running", 0);
            return;
        }

        int[] decodeSize = computeScaledSize();
        int fps = getSelectedFps();
        long frameUs = Math.max(1L, 1_000_000L / Math.max(1, fps));

        player = new H264TcpPlayer(
                surface,
                new StatusListener() {
                    @Override
                    public void onStatus(String state, String info, long bps) {
                        runOnUiThread(() -> updateStatus(state, info, bps));
                    }

                    @Override
                    public void onStats(String line) {
                        runOnUiThread(() -> updateStatsLine(line));
                    }

                    @Override
                    public void onClientMetrics(ClientMetricsSample metrics) {
                        pushClientMetricsAsync(metrics);
                    }
                },
                decodeSize[0],
                decodeSize[1],
                frameUs
        );
        player.start();
        hideSettingsPanel();
    }

    private void stopLiveView() {
        if (player != null) {
            player.stop();
            player = null;
        }
        releaseMediaPlayer();
        hideCursorOverlay();
        updateStatsLine("fps in/out: - | drops: - | late: - | q(t/d/r): -/-/- | reconnects: -");
        updateStatus(STATE_IDLE, "stopped", 0);
    }

    private void startPublicVideoTest() {
        if (surface == null || !surface.isValid()) {
            updateStatus(STATE_ERROR, "surface not ready yet", 0);
            return;
        }

        if (player != null) {
            player.stop();
            player = null;
        }
        releaseMediaPlayer();

        try {
            updateStatus(STATE_CONNECTING, "loading public test video", 0);
            updateStatsLine("source: public test player");
            MediaPlayer mp = new MediaPlayer();
            mediaPlayer = mp;
            mp.setSurface(surface);
            mp.setDataSource(TEST_VIDEO_URL);
            mp.setLooping(true);
            mp.setVolume(0f, 0f);

            mp.setOnPreparedListener(ready -> {
                Log.i(TAG, "public test video prepared");
                ready.start();
                updateStatus(STATE_STREAMING, "public test video playing", 0);
            });
            mp.setOnCompletionListener(done -> {
                Log.i(TAG, "public test video completed");
                updateStatus(STATE_IDLE, "public test completed", 0);
            });
            mp.setOnErrorListener((errPlayer, what, extra) -> {
                Log.e(TAG, "public test error what=" + what + " extra=" + extra);
                updateStatus(STATE_ERROR, "public test error: " + what + "/" + extra, 0);
                return true;
            });
            mp.setOnInfoListener((infoPlayer, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) {
                    updateStatus(STATE_CONNECTING, "public test buffering", 0);
                } else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END) {
                    updateStatus(STATE_STREAMING, "public test video playing", 0);
                }
                return false;
            });
            mp.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "failed to start public test video", e);
            updateStatus(STATE_ERROR, "public test failed: " + e.getClass().getSimpleName(), 0);
            releaseMediaPlayer();
        }
    }

    private void releaseMediaPlayer() {
        if (mediaPlayer != null) {
            try {
                mediaPlayer.stop();
            } catch (Exception ignored) {
            }
            try {
                mediaPlayer.reset();
            } catch (Exception ignored) {
            }
            try {
                mediaPlayer.release();
            } catch (Exception ignored) {
            }
            mediaPlayer = null;
        }
    }

    private void toggleSettingsPanel() {
        if (settingsVisible) {
            hideSettingsPanel();
        } else {
            showSettingsPanel();
        }
    }

    private void showSettingsPanel() {
        if (settingsVisible) {
            return;
        }
        settingsVisible = true;
        settingsPanel.setVisibility(View.VISIBLE);
        settingsPanel.post(() -> {
            settingsPanel.setTranslationY(-settingsPanel.getHeight());
            settingsPanel.setAlpha(0f);
            settingsPanel.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(180)
                    .start();
        });
    }

    private void hideSettingsPanel() {
        if (!settingsVisible) {
            return;
        }
        settingsVisible = false;
        settingsPanel.animate()
                .translationY(-settingsPanel.getHeight())
                .alpha(0f)
                .setDuration(160)
                .withEndAction(() -> {
                    settingsPanel.setVisibility(View.GONE);
                    settingsPanel.setAlpha(1f);
                    settingsPanel.setTranslationY(0f);
                })
                .start();
    }

    private void toggleFullscreen() {
        setFullscreen(!isFullscreen);
    }

    private void setFullscreen(boolean enable) {
        isFullscreen = enable;

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());

        if (enable) {
            topBar.setVisibility(View.GONE);
            statusPanel.setVisibility(View.GONE);
            hideSettingsPanel();

            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            if (controller != null) {
                controller.setSystemBarsBehavior(
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
                controller.hide(WindowInsetsCompat.Type.systemBars());
            }
            return;
        }

        topBar.setVisibility(View.VISIBLE);
        statusPanel.setVisibility(View.VISIBLE);

        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        if (controller != null) {
            controller.show(WindowInsetsCompat.Type.systemBars());
        }
    }

    private void toggleCursorOverlayMode() {
        cursorOverlayEnabled = !cursorOverlayEnabled;
        cursorOverlayButton.setText(cursorOverlayEnabled ? "Local Cursor Overlay ON" : "Local Cursor Overlay OFF");
        if (!cursorOverlayEnabled) {
            hideCursorOverlay();
        }
        saveSettings();
    }

    private void updateCursorOverlay(float x, float y, int action) {
        if (cursorOverlay == null || !cursorOverlayEnabled) {
            return;
        }
        cursorOverlay.setX(x - (cursorOverlay.getWidth() / 2f));
        cursorOverlay.setY(y - (cursorOverlay.getHeight() / 2f));
        cursorOverlay.setVisibility(View.VISIBLE);

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            cursorOverlay.removeCallbacks(this::hideCursorOverlay);
            cursorOverlay.postDelayed(this::hideCursorOverlay, 400);
        }
    }

    private void hideCursorOverlay() {
        if (cursorOverlay != null) {
            cursorOverlay.setVisibility(View.GONE);
        }
    }

    private void updateStatus(String state, String info, long bps) {
        lastUiState = state == null ? STATE_IDLE : state;
        lastUiInfo = info == null ? "-" : info;
        lastUiBps = bps;
        refreshStatusText();
        if (STATE_ERROR.equals(lastUiState) && isCriticalUiInfo(lastUiInfo)) {
            String line = "status=" + lastUiState + " info=" + lastUiInfo + " bps=" + bps;
            appendLiveLogError(line);
            Log.e(TAG, line);
        }
    }

    private boolean isCriticalUiInfo(String info) {
        if (info == null) {
            return false;
        }
        String text = info.toLowerCase(Locale.US);
        return text.contains("offline")
                || text.contains("ioexception")
                || text.contains("stream error")
                || text.contains("failed")
                || text.contains("disconnected");
    }

    private void appendLiveLogInfo(String line) {
        appendLiveLog("I", line);
    }

    private void appendLiveLogWarn(String line) {
        appendLiveLog("W", line);
    }

    private void appendLiveLogError(String line) {
        appendLiveLog("E", line);
    }

    private void appendLiveLog(String level, String line) {
        if (line == null || line.trim().isEmpty()) {
            return;
        }

        Runnable task = () -> {
            if (liveLogText == null) {
                return;
            }

            int color = Color.parseColor("#9CA3AF");
            if ("I".equals(level)) {
                color = Color.parseColor("#166534");
            } else if ("W".equals(level)) {
                color = Color.parseColor("#F59E0B");
            } else if ("E".equals(level)) {
                color = Color.parseColor("#7F1D1D");
            }

            String text = "[" + level + "] " + line + "\n";
            int start = liveLogBuffer.length();
            liveLogBuffer.append(text);
            liveLogBuffer.setSpan(
                    new ForegroundColorSpan(color),
                    start,
                    liveLogBuffer.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            );

            int linesToTrim = countLines(liveLogBuffer) - LIVE_LOG_MAX_LINES;
            while (linesToTrim > 0) {
                int newline = liveLogBuffer.toString().indexOf('\n');
                if (newline < 0) {
                    break;
                }
                liveLogBuffer.delete(0, newline + 1);
                linesToTrim--;
            }

            liveLogText.setText(liveLogBuffer);
            liveLogText.setVisibility(liveLogVisible ? View.VISIBLE : View.GONE);
        };

        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            runOnUiThread(task);
        }
    }

    private int countLines(CharSequence text) {
        if (text == null || text.length() == 0) {
            return 0;
        }
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            }
        }
        return lines;
    }

    private void refreshStatusText() {
        int color = ledColorForState(lastUiState);
        String host = daemonHostName == null || daemonHostName.trim().isEmpty() ? "-" : daemonHostName;
        String transport = "USB:" + (daemonReachable ? "Connected" : "Disconnected")
                + " | Host:" + host
                + " | Stream:" + daemonState;

        statusText.setText(lastUiState.toUpperCase(Locale.US));
        if (lastUiInfo == null || lastUiInfo.trim().isEmpty()) {
            detailText.setText(transport);
        } else {
            detailText.setText(lastUiInfo + " | " + transport);
        }
        bpsText.setText("throughput: " + formatBps(lastUiBps));

        if (statusLed.getBackground() instanceof GradientDrawable) {
            GradientDrawable drawable = (GradientDrawable) statusLed.getBackground().mutate();
            drawable.setColor(color);
        } else {
            statusLed.setBackgroundColor(color);
        }
    }

    private void updateStatsLine(String line) {
        statsText.setText(line == null || line.trim().isEmpty()
                ? "fps in/out: - | drops: - | late: - | q(t/d/r): -/-/- | reconnects: -"
                : line);
    }

    private void updatePerfHudUnavailable() {
        if (perfHudText == null) {
            return;
        }
        perfHudText.setText("HUD OFFLINE\nwaiting for host metrics...");
        perfHudText.setTextColor(Color.parseColor("#FCA5A5"));
        if (perfHudPanel != null) {
            perfHudPanel.setAlpha(0.92f);
        }
        emitHudDebugAdb("state=offline waiting_metrics=1");
    }

    private void updatePerfHud(JSONObject metrics) {
        if (perfHudText == null) {
            return;
        }
        if (metrics == null) {
            updatePerfHudUnavailable();
            return;
        }

        JSONObject kpi = metrics.optJSONObject("kpi");
        JSONObject latest = metrics.optJSONObject("latest_client_metrics");
        JSONObject limits = metrics.optJSONObject("queue_limits");
        long frameInHost = metrics.optLong("frame_in", 0);
        long frameOutHost = metrics.optLong("frame_out", 0);
        long streamUptimeSec = metrics.optLong("stream_uptime_sec", 0);

        double targetFps = kpi != null ? kpi.optDouble("target_fps", getSelectedFps()) : getSelectedFps();
        double presentFps = kpi != null ? kpi.optDouble("present_fps", 0.0) : 0.0;
        double frametimeP95 = kpi != null ? kpi.optDouble("frametime_ms_p95", 0.0) : 0.0;
        double decodeP95 = kpi != null ? kpi.optDouble("decode_time_ms_p95", 0.0) : 0.0;
        double renderP95 = kpi != null ? kpi.optDouble("render_time_ms_p95", 0.0) : 0.0;
        double e2eP95 = kpi != null ? kpi.optDouble("e2e_latency_ms_p95", 0.0) : 0.0;

        int qT = latest != null ? latest.optInt("transport_queue_depth", 0) : 0;
        int qD = latest != null ? latest.optInt("decode_queue_depth", 0) : 0;
        int qR = latest != null ? latest.optInt("render_queue_depth", 0) : 0;

        int qTMax = limits != null ? limits.optInt("transport_queue_max", TRANSPORT_QUEUE_MAX_FRAMES) : TRANSPORT_QUEUE_MAX_FRAMES;
        int qDMax = limits != null ? limits.optInt("decode_queue_max", DECODE_QUEUE_MAX_FRAMES) : DECODE_QUEUE_MAX_FRAMES;
        int qRMax = limits != null ? limits.optInt("render_queue_max", RENDER_QUEUE_MAX_FRAMES) : RENDER_QUEUE_MAX_FRAMES;

        int adaptiveLevel = metrics.optInt("adaptive_level", 0);
        String adaptiveAction = metrics.optString("adaptive_action", "hold");
        long drops = metrics.optLong("drops", 0);
        long bpHigh = metrics.optLong("backpressure_high_events", 0);
        long bpRecover = metrics.optLong("backpressure_recover_events", 0);
        String reason = metrics.optString("adaptive_reason", "");
        if (reason.length() > 44) {
            reason = reason.substring(0, 44) + "...";
        }

        String hud = String.format(
                Locale.US,
                "HUD %s\nfps %.0f/%.1f frame %.2fms\ndec %.2fms ren %.2fms e2e %.2fms\nq %d/%d/%d max %d/%d/%d\nadapt L%d %s\ndrops %d bp %d/%d\n%s",
                daemonReachable ? "LIVE" : "DEGRADED",
                targetFps,
                presentFps,
                frametimeP95,
                decodeP95,
                renderP95,
                e2eP95,
                qT,
                qD,
                qR,
                qTMax,
                qDMax,
                qRMax,
                adaptiveLevel,
                adaptiveAction,
                drops,
                bpHigh,
                bpRecover,
                reason.isEmpty() ? "-" : reason
        );
        perfHudText.setText(hud);

        boolean highPressure = decodeP95 > 12.0 || renderP95 > 7.0 || qT >= qTMax || qD >= qDMax || qR >= qRMax;
        if (highPressure) {
            perfHudText.setTextColor(Color.parseColor("#FCA5A5"));
        } else if (adaptiveAction.startsWith("degrade")) {
            perfHudText.setTextColor(Color.parseColor("#FDE68A"));
        } else {
            perfHudText.setTextColor(Color.parseColor("#BBF7D0"));
        }
        if (perfHudPanel != null) {
            perfHudPanel.setAlpha(0.95f);
        }

        String compact = String.format(
                Locale.US,
                "state=%s run_id=%d up=%ds stream_up=%ds host_in_out=%d/%d fps_target=%.0f fps_present=%.1f frame_p95=%.2f dec_p95=%.2f ren_p95=%.2f e2e_p95=%.2f q=%d/%d/%d qmax=%d/%d/%d adapt=L%d:%s drops=%d bp=%d/%d reason=%s",
                daemonState,
                daemonRunId,
                daemonUptimeSec,
                streamUptimeSec,
                frameInHost,
                frameOutHost,
                targetFps,
                presentFps,
                frametimeP95,
                decodeP95,
                renderP95,
                e2eP95,
                qT,
                qD,
                qR,
                qTMax,
                qDMax,
                qRMax,
                adaptiveLevel,
                adaptiveAction,
                drops,
                bpHigh,
                bpRecover,
                reason.isEmpty() ? "-" : reason
        );
        emitHudDebugAdb(compact);
    }

    private void startPreflightPulse() {
        uiHandler.removeCallbacks(preflightPulseTask);
        uiHandler.post(preflightPulseTask);
    }

    private void stopPreflightPulse() {
        uiHandler.removeCallbacks(preflightPulseTask);
    }

    private void setPreflightVisible(boolean visible) {
        if (preflightOverlay == null) {
            return;
        }
        if (visible) {
            preflightOverlay.setVisibility(View.VISIBLE);
            preflightOverlay.setAlpha(1f);
            return;
        }
        preflightOverlay.animate()
                .alpha(0f)
                .setDuration(180)
                .withEndAction(() -> {
                    if (preflightOverlay != null) {
                        preflightOverlay.setVisibility(View.GONE);
                        preflightOverlay.setAlpha(1f);
                    }
                })
                .start();
    }

    private void updatePreflightOverlay() {
        if (preflightOverlay == null || preflightTitleText == null || preflightBodyText == null || preflightHintText == null) {
            return;
        }

        boolean usbOk = daemonReachable;
        boolean hostOk = daemonReachable;
        boolean surfaceOk = surfaceReady;
        boolean hwOk = hwAvcDecodeAvailable;
        boolean streamReady = daemonReachable && (
                "STREAMING".equals(daemonState)
                        || "IDLE".equals(daemonState)
                        || "STARTING".equals(daemonState)
                        || "RECONNECTING".equals(daemonState)
        );

        boolean ready = usbOk && hostOk && surfaceOk && hwOk && streamReady;
        String spin = spinnerGlyph();

        String body = String.format(
                Locale.US,
                "[%s] usb_link\n[%s] host_api\n[%s] surface\n[%s] hw_decode_avc\n[%s] stream_ready",
                usbOk ? "OK" : "..",
                hostOk ? "OK" : "..",
                surfaceOk ? "OK" : "..",
                hwOk ? "OK" : "NO",
                streamReady ? "OK" : ".."
        );
        preflightBodyText.setText(body);

        if (!ready) {
            preflightComplete = false;
            setPreflightVisible(true);
            if (!usbOk) {
                preflightTitleText.setText("WBEAM PRE-FLIGHT " + spin + " WAITING CONTROL LINK");
                preflightHintText.setText("busy: adb reverse/control api unreachable");
                preflightHintText.setTextColor(Color.parseColor("#FCA5A5"));
            } else if (!surfaceOk) {
                preflightTitleText.setText("WBEAM PRE-FLIGHT " + spin + " WAITING SURFACE");
                preflightHintText.setText("busy: waiting for preview surface init");
                preflightHintText.setTextColor(Color.parseColor("#FDE68A"));
            } else if (!hwOk) {
                preflightTitleText.setText("WBEAM PRE-FLIGHT " + spin + " DECODER CHECK");
                preflightHintText.setText("warning: hardware AVC not detected, fallback may stutter");
                preflightHintText.setTextColor(Color.parseColor("#FCA5A5"));
            } else if (!streamReady) {
                preflightTitleText.setText("WBEAM PRE-FLIGHT " + spin + " WAITING STREAM");
                preflightHintText.setText("busy: host state=" + daemonState.toLowerCase(Locale.US));
                preflightHintText.setTextColor(Color.parseColor("#FDE68A"));
            } else {
                preflightTitleText.setText("WBEAM PRE-FLIGHT " + spin + " RUNNING");
                preflightHintText.setText("busy: validating startup path");
                preflightHintText.setTextColor(Color.parseColor("#FDE68A"));
            }
            return;
        }

        if (!preflightComplete) {
            preflightComplete = true;
            preflightTitleText.setText("WBEAM PRE-FLIGHT DONE");
            preflightHintText.setText("ready: starting live pipeline");
            preflightHintText.setTextColor(Color.parseColor("#BBF7D0"));
            preflightBodyText.setText(body);
            uiHandler.postDelayed(() -> {
                if (preflightComplete) {
                    setPreflightVisible(false);
                }
            }, 500);
        }
    }

    private String spinnerGlyph() {
        switch (preflightAnimTick) {
            case 1:
                return "/";
            case 2:
                return "-";
            case 3:
                return "\\";
            default:
                return "|";
        }
    }

    private boolean hasHardwareAvcDecoder() {
        try {
            MediaCodecInfo[] infos;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                infos = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
            } else {
                int count = MediaCodecList.getCodecCount();
                infos = new MediaCodecInfo[count];
                for (int i = 0; i < count; i++) {
                    infos[i] = MediaCodecList.getCodecInfoAt(i);
                }
            }

            for (MediaCodecInfo info : infos) {
                if (info == null || info.isEncoder()) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if ("video/avc".equalsIgnoreCase(type)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            if (info.isHardwareAccelerated()) {
                                return true;
                            }
                        } else {
                            String name = info.getName().toLowerCase(Locale.US);
                            if (!name.startsWith("omx.google.")
                                    && !name.startsWith("c2.android.")
                                    && !name.contains("sw")) {
                                return true;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "failed to inspect codecs", e);
        }
        return false;
    }

    private void emitHudDebugAdb(String snapshot) {
        if (snapshot == null || snapshot.trim().isEmpty()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastHudAdbLogAt < HUD_ADB_LOG_INTERVAL_MS) {
            return;
        }
        if (snapshot.equals(lastHudAdbSnapshot)) {
            return;
        }
        lastHudAdbLogAt = now;
        lastHudAdbSnapshot = snapshot;
        Log.i(TAG, "HUDDBG " + snapshot);
    }

    private int ledColorForState(String state) {
        if (STATE_STREAMING.equals(state)) {
            return Color.parseColor("#22C55E");
        }
        if (STATE_CONNECTING.equals(state)) {
            return Color.parseColor("#F59E0B");
        }
        return Color.parseColor("#EF4444");
    }

    private String formatBps(long bps) {
        if (bps <= 0) {
            return "-";
        }
        if (bps >= 1024L * 1024L) {
            return String.format(Locale.US, "%.2f MB/s", bps / (1024.0 * 1024.0));
        }
        if (bps >= 1024L) {
            return String.format(Locale.US, "%.1f KB/s", bps / 1024.0);
        }
        return bps + " B/s";
    }

    private int[] computeScaledSize() {
        String profile = getSelectedProfile();
        int baseW = "ultra".equals(profile) ? 2560 : 1920;
        int baseH = "ultra".equals(profile) ? 1440 : 1080;

        int scale = getResolutionScalePercent();
        int w = Math.max(640, (baseW * scale / 100) & ~1);
        int h = Math.max(360, (baseH * scale / 100) & ~1);
        return new int[]{w, h};
    }

    private int getResolutionScalePercent() {
        return 50 + resolutionSeek.getProgress();
    }

    private int getSelectedFps() {
        return 24 + fpsSeek.getProgress();
    }

    private int getSelectedBitrateMbps() {
        return 5 + bitrateSeek.getProgress();
    }

    private String getSelectedProfile() {
        return String.valueOf(profileSpinner.getSelectedItem());
    }

    private String getSelectedEncoder() {
        return String.valueOf(encoderSpinner.getSelectedItem());
    }

    private String getSelectedCursorMode() {
        return String.valueOf(cursorSpinner.getSelectedItem());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void setSpinnerSelection(Spinner spinner, String[] options, String value) {
        int idx = 0;
        for (int i = 0; i < options.length; i++) {
            if (options[i].equals(value)) {
                idx = i;
                break;
            }
        }
        spinner.setSelection(idx, false);
    }

    private interface StatusListener {
        void onStatus(String state, String info, long bps);

        void onStats(String line);

        void onClientMetrics(ClientMetricsSample metrics);
    }

    private static final class ClientMetricsSample {
        final double recvFps;
        final double decodeFps;
        final double presentFps;
        final long recvBps;
        final double decodeMsP50;
        final double decodeMsP95;
        final double renderMsP95;
        final double e2eP50;
        final double e2eP95;
        final int transportQueueDepth;
        final int decodeQueueDepth;
        final int renderQueueDepth;
        final int jitterBufferFrames;
        final long droppedFrames;
        final long tooLateFrames;
        final long timestampMs;

        ClientMetricsSample(
                double recvFps,
                double decodeFps,
                double presentFps,
                long recvBps,
                double decodeMsP50,
                double decodeMsP95,
                double renderMsP95,
                double e2eP50,
                double e2eP95,
                int transportQueueDepth,
                int decodeQueueDepth,
                int renderQueueDepth,
                int jitterBufferFrames,
                long droppedFrames,
                long tooLateFrames
        ) {
            this.recvFps = recvFps;
            this.decodeFps = decodeFps;
            this.presentFps = presentFps;
            this.recvBps = recvBps;
            this.decodeMsP50 = decodeMsP50;
            this.decodeMsP95 = decodeMsP95;
            this.renderMsP95 = renderMsP95;
            this.e2eP50 = e2eP50;
            this.e2eP95 = e2eP95;
            this.transportQueueDepth = transportQueueDepth;
            this.decodeQueueDepth = decodeQueueDepth;
            this.renderQueueDepth = renderQueueDepth;
            this.jitterBufferFrames = jitterBufferFrames;
            this.droppedFrames = droppedFrames;
            this.tooLateFrames = tooLateFrames;
            this.timestampMs = System.currentTimeMillis();
        }

        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("recv_fps", recvFps);
            json.put("decode_fps", decodeFps);
            json.put("present_fps", presentFps);
            json.put("recv_bps", recvBps);
            json.put("decode_time_ms_p50", decodeMsP50);
            json.put("decode_time_ms_p95", decodeMsP95);
            json.put("render_time_ms_p95", renderMsP95);
            json.put("e2e_latency_ms_p50", e2eP50);
            json.put("e2e_latency_ms_p95", e2eP95);
            json.put("transport_queue_depth", transportQueueDepth);
            json.put("decode_queue_depth", decodeQueueDepth);
            json.put("render_queue_depth", renderQueueDepth);
            json.put("jitter_buffer_frames", jitterBufferFrames);
            json.put("dropped_frames", droppedFrames);
            json.put("too_late_frames", tooLateFrames);
            json.put("timestamp_ms", timestampMs);
            return json;
        }
    }

    private static final class H264TcpPlayer {
        private final Surface surface;
        private final StatusListener statusListener;
        private final int decodeWidth;
        private final int decodeHeight;
        private final long frameUs;

        private volatile boolean running;
        private volatile long reconnectDelayMs = 800;
        private Thread thread;
        private Socket socket;
        private long reconnects = 0;
        private long droppedTotal = 0;
        private long tooLateTotal = 0;

        H264TcpPlayer(Surface surface, StatusListener statusListener, int decodeWidth, int decodeHeight, long frameUs) {
            this.surface = surface;
            this.statusListener = statusListener;
            this.decodeWidth = decodeWidth;
            this.decodeHeight = decodeHeight;
            this.frameUs = frameUs;
        }

        void start() {
            if (running) {
                return;
            }
            running = true;
            thread = new Thread(this::runLoop, "wbeam-h264-player");
            thread.start();
        }

        void stop() {
            running = false;
            closeSocket();
            if (thread != null) {
                thread.interrupt();
            }
        }

        boolean isRunning() {
            return running;
        }

        private void runLoop() {
            while (running) {
                MediaCodec codec = null;
                try {
                    statusListener.onStatus(STATE_CONNECTING, "connecting to " + HOST + ":" + PORT, 0);
                    statusListener.onStats(
                            "fps in/out: - | drops: " + droppedTotal
                                    + " | late: " + tooLateTotal
                                    + " | reconnects: " + reconnects
                    );

                    socket = new Socket();
                    socket.connect(new InetSocketAddress(HOST, PORT), 2000);
                    socket.setTcpNoDelay(true);

                    codec = MediaCodec.createDecoderByType("video/avc");
                    MediaFormat format = MediaFormat.createVideoFormat("video/avc", decodeWidth, decodeHeight);
                    codec.configure(format, surface, null, 0);
                    codec.start();

                    statusListener.onStatus(STATE_STREAMING, "connected", 0);
                    decodeLoop(socket.getInputStream(), codec);
                } catch (Exception e) {
                    if (running) {
                        reconnects++;
                        reconnectDelayMs = Math.min(5000, reconnectDelayMs + 400);
                        if (isExpectedStreamClose(e)) {
                            Log.w(TAG, "stream worker reconnect: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                            statusListener.onStatus(STATE_CONNECTING, "stream reconnecting", 0);
                        } else {
                            Log.e(TAG, "stream worker failed", e);
                            statusListener.onStatus(STATE_ERROR, "stream error: " + e.getClass().getSimpleName(), 0);
                        }
                        statusListener.onStats(
                                "fps in/out: - | drops: " + droppedTotal
                                        + " | late: " + tooLateTotal
                                        + " | reconnects: " + reconnects
                        );
                    }
                } finally {
                    closeSocket();
                    if (codec != null) {
                        try {
                            codec.stop();
                        } catch (Exception ignored) {
                        }
                        try {
                            codec.release();
                        } catch (Exception ignored) {
                        }
                    }
                }

                if (running) {
                    SystemClock.sleep(reconnectDelayMs);
                }
            }
        }

        private void decodeLoop(InputStream input, MediaCodec codec) throws IOException {
            // C2: ring-buffer with sHead/sTail pointers – eliminates per-NAL System.arraycopy.
            // Buffer compacts only when the write pointer reaches the end (≈ every 20 frames
            // at 720p/12 Mbps). 512 KB ≈ 20 frames; bounded by design (task-id C2, EPIC E).
            byte[] readBuf  = new byte[64 * 1024];
            byte[] streamBuf = new byte[512 * 1024]; // C2: was 4 MB linear; now ring, sHead/sTail
            int sHead = 0; // first unconsumed byte (inclusive)
            int sTail = 0; // one-past last valid byte; avail = sTail - sHead
            int streamMode = -1; // -1 unknown, 0 annexb, 1 avcc
            int avgNalSize = 1200;
            int pendingDecodeQueue = 0;
            int renderQueueDepth   = 0;

            long frames        = 0;
            long bytes         = 0;
            long inFrames      = 0;
            long outFrames     = 0;
            long droppedSec    = 0;
            long tooLateSec    = 0;
            long decodeNsTotal = 0;
            long decodeNsMax   = 0;
            long renderNsMax   = 0;
            long lastLog = SystemClock.elapsedRealtime();
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
            DrainStats drainStats = new DrainStats();

            while (running) {
                int count = input.read(readBuf);
                if (count < 0) {
                    throw new IOException("stream closed");
                }
                if (count == 0) {
                    continue;
                }

                // C2: compact only when tail would overflow – avoids per-NAL memmove.
                int avail = sTail - sHead;
                if (sTail + count > streamBuf.length) {
                    if (avail + count > streamBuf.length) {
                        // Genuinely full: drop oldest data to make room, count as drop.
                        int keep = streamBuf.length - count;
                        if (keep <= 0) {
                            sHead = 0; sTail = 0; avail = 0;
                        } else {
                            int newHead = sHead + (avail - keep);
                            System.arraycopy(streamBuf, newHead, streamBuf, 0, keep);
                            sHead = 0; sTail = keep; avail = keep;
                        }
                        droppedSec++;
                    } else {
                        // Free space exists at front: compact in one memmove.
                        if (avail > 0) System.arraycopy(streamBuf, sHead, streamBuf, 0, avail);
                        sHead = 0; sTail = avail;
                    }
                }
                System.arraycopy(readBuf, 0, streamBuf, sTail, count);
                sTail += count;
                bytes += count;

                avail = sTail - sHead;
                if (streamMode < 0 && avail >= 8) {
                    int probe = findStartCode(streamBuf, sHead, Math.min(sHead + 128, sTail));
                    streamMode = (probe >= 0) ? 0 : 1;
                }

                if (streamMode == 1) {
                    // ── AVCC mode: [u32 length][payload] ─────────────────────────────────
                    // C2: sHead += 4 + nalSize replaces System.arraycopy(streamBuf, consumed, …)
                    while ((sTail - sHead) >= 4) {
                        int nalSize =
                                ((streamBuf[sHead]     & 0xFF) << 24) |
                                ((streamBuf[sHead + 1] & 0xFF) << 16) |
                                ((streamBuf[sHead + 2] & 0xFF) << 8)  |
                                ((streamBuf[sHead + 3] & 0xFF));

                        if (nalSize <= 0 || nalSize > streamBuf.length) {
                            sHead += 1; // skip invalid length byte – no arraycopy
                            droppedSec++;
                            continue;
                        }
                        if ((sTail - sHead) < 4 + nalSize) {
                            break; // wait for more data
                        }

                        long t0 = SystemClock.elapsedRealtimeNanos();
                        if (queueNal(codec, streamBuf, sHead + 4, nalSize, frames * frameUs)) {
                            long decodeNs = SystemClock.elapsedRealtimeNanos() - t0;
                            decodeNsTotal += decodeNs;
                            decodeNsMax = Math.max(decodeNsMax, decodeNs);
                            avgNalSize = ((avgNalSize * 7) + nalSize) / 8;
                            inFrames++;
                            pendingDecodeQueue++;
                        } else {
                            droppedSec++;
                        }
                        frames++;
                        drainLatestFrame(codec, bufferInfo, drainStats);
                        pendingDecodeQueue = Math.max(0, pendingDecodeQueue - drainStats.releasedCount);
                        outFrames   += drainStats.renderedCount;
                        tooLateSec  += drainStats.droppedLateCount;
                        renderNsMax  = Math.max(renderNsMax, drainStats.renderNsMax);
                        renderQueueDepth = drainStats.renderedCount > 0 ? 1 : 0;

                        sHead += 4 + nalSize; // C2: zero-copy advance
                    }
                } else {
                    // ── AnnexB mode: [0 0 0 1 | 0 0 1][nal][0 0 0 1 | 0 0 1][nal]… ──────
                    // C2: advance sHead to first start-code (drop garbage), then advance per NAL.
                    int nalStart = findStartCode(streamBuf, sHead, sTail);
                    if (nalStart < 0) {
                        // No start-code yet – keep last 3 bytes for cross-read-boundary match.
                        sHead = Math.max(sHead, sTail - 3);
                    } else {
                        sHead = nalStart; // skip garbage before first start-code – no arraycopy

                        while (true) {
                            int next = findStartCode(streamBuf, sHead + 3, sTail);
                            if (next < 0) {
                                break; // incomplete NAL – wait for more data
                            }

                            int nalSize = next - sHead;
                            if (nalSize > 0) {
                                long t0 = SystemClock.elapsedRealtimeNanos();
                                if (queueNal(codec, streamBuf, sHead, nalSize, frames * frameUs)) {
                                    long decodeNs = SystemClock.elapsedRealtimeNanos() - t0;
                                    decodeNsTotal += decodeNs;
                                    decodeNsMax = Math.max(decodeNsMax, decodeNs);
                                    avgNalSize = ((avgNalSize * 7) + nalSize) / 8;
                                    inFrames++;
                                    pendingDecodeQueue++;
                                } else {
                                    droppedSec++;
                                }
                                frames++;
                                drainLatestFrame(codec, bufferInfo, drainStats);
                                pendingDecodeQueue = Math.max(0, pendingDecodeQueue - drainStats.releasedCount);
                                outFrames   += drainStats.renderedCount;
                                tooLateSec  += drainStats.droppedLateCount;
                                renderNsMax  = Math.max(renderNsMax, drainStats.renderNsMax);
                                renderQueueDepth = drainStats.renderedCount > 0 ? 1 : 0;
                            }
                            sHead = next; // C2: zero-copy advance to next start-code
                        }
                        // sHead now points at the last (incomplete) start-code: retained for next read.
                    }
                }

                long now = SystemClock.elapsedRealtime();
                if (now - lastLog >= 1000) {
                    droppedTotal += droppedSec;
                    tooLateTotal += tooLateSec;
                    reconnectDelayMs = 800;
                    statusListener.onStatus(STATE_STREAMING, "rendering live desktop", bytes);
                    statusListener.onStats(
                            "fps in/out: " + inFrames + "/" + outFrames
                                    + " | drops: " + droppedTotal
                                    + " | late: " + tooLateTotal
                                    + " | q(t/d/r): "
                                    + estimateTransportDepthFrames(sTail - sHead, avgNalSize) + "/"
                                    + Math.min(DECODE_QUEUE_MAX_FRAMES, pendingDecodeQueue) + "/"
                                    + renderQueueDepth
                                    + " | reconnects: " + reconnects
                    );

                    double decodeMsP50 = inFrames > 0
                            ? (decodeNsTotal / 1_000_000.0) / inFrames
                            : 0.0;
                    double decodeMsP95 = Math.max(decodeMsP50, decodeNsMax / 1_000_000.0);
                    double renderMsP95 = renderNsMax / 1_000_000.0;
                    statusListener.onClientMetrics(
                            new ClientMetricsSample(
                                    inFrames,
                                    inFrames,
                                    outFrames,
                                    bytes,
                                    decodeMsP50,
                                    decodeMsP95,
                                    renderMsP95,
                                    0.0,
                                    0.0,
                                    estimateTransportDepthFrames(sTail - sHead, avgNalSize),
                                    Math.min(DECODE_QUEUE_MAX_FRAMES, pendingDecodeQueue),
                                    Math.min(RENDER_QUEUE_MAX_FRAMES, renderQueueDepth),
                                    0,
                                    droppedTotal,
                                    tooLateTotal
                            )
                    );
                    bytes         = 0;
                    inFrames      = 0;
                    outFrames     = 0;
                    droppedSec    = 0;
                    tooLateSec    = 0;
                    decodeNsTotal = 0;
                    decodeNsMax   = 0;
                    renderNsMax   = 0;
                    lastLog = now;
                }
            }
        }


        private static boolean queueNal(MediaCodec codec, byte[] data, int offset, int size, long ptsUs) {
            int inputIndex = codec.dequeueInputBuffer(10_000);
            if (inputIndex < 0) {
                return false;
            }
            ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
            if (inputBuffer == null) {
                codec.queueInputBuffer(inputIndex, 0, 0, ptsUs, 0);
                return false;
            }
            inputBuffer.clear();
            if (size > inputBuffer.remaining()) {
                codec.queueInputBuffer(inputIndex, 0, 0, ptsUs, 0);
                return false;
            }
            inputBuffer.put(data, offset, size);
            codec.queueInputBuffer(inputIndex, 0, size, ptsUs, 0);
            return true;
        }

        private static void drainLatestFrame(
                MediaCodec codec,
                MediaCodec.BufferInfo info,
                DrainStats stats
        ) {
            stats.reset();
            int latestRenderableIndex = -1;

            while (true) {
                int outputIndex = codec.dequeueOutputBuffer(info, 0);
                if (outputIndex >= 0) {
                    stats.releasedCount++;
                    boolean renderable = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
                    if (!renderable) {
                        codec.releaseOutputBuffer(outputIndex, false);
                        continue;
                    }

                    if (latestRenderableIndex >= 0) {
                        codec.releaseOutputBuffer(latestRenderableIndex, false);
                        stats.droppedLateCount++;
                    }
                    latestRenderableIndex = outputIndex;
                    continue;
                }
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER ||
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    break;
                }
                break;
            }

            if (latestRenderableIndex >= 0) {
                long renderStartNs = SystemClock.elapsedRealtimeNanos();
                codec.releaseOutputBuffer(latestRenderableIndex, true);
                stats.renderedCount = 1;
                stats.renderNsMax = SystemClock.elapsedRealtimeNanos() - renderStartNs;
            }
        }

        private static int estimateTransportDepthFrames(int streamLen, int avgNalSize) {
            int denom = Math.max(512, avgNalSize);
            if (streamLen <= 0) {
                return 0;
            }
            return Math.min(8, streamLen / denom);
        }

        private static final class DrainStats {
            int releasedCount;
            int renderedCount;
            int droppedLateCount;
            long renderNsMax;

            void reset() {
                releasedCount = 0;
                renderedCount = 0;
                droppedLateCount = 0;
                renderNsMax = 0;
            }
        }

        private static int findStartCode(byte[] data, int from, int toExclusive) {
            int limit = toExclusive - 3;
            for (int i = Math.max(0, from); i <= limit; i++) {
                if (data[i] == 0 && data[i + 1] == 0) {
                    if (data[i + 2] == 1) {
                        return i;
                    }
                    if (i + 3 < toExclusive && data[i + 2] == 0 && data[i + 3] == 1) {
                        return i;
                    }
                }
            }
            return -1;
        }

        private void closeSocket() {
            Socket current = socket;
            socket = null;
            if (current != null) {
                try {
                    current.close();
                } catch (IOException ignored) {
                }
            }
        }

        private static boolean isExpectedStreamClose(Exception e) {
            if (!(e instanceof IOException)) {
                return false;
            }
            String msg = e.getMessage();
            if (msg == null) {
                return false;
            }
            String m = msg.toLowerCase(Locale.US);
            return m.contains("stream closed")
                    || m.contains("connection reset")
                    || m.contains("broken pipe")
                    || m.contains("software caused connection abort");
        }
    }
}
