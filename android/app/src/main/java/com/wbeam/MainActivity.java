package com.wbeam;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaCodec;
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
    private View debugControlsRow;
    private View statusLed;
    private View cursorOverlay;
    private TextView statusText;
    private TextView detailText;
    private TextView bpsText;
    private TextView statsText;
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
    private boolean statusPollInFlight = false;
    private long lastAutoStartAt = 0L;
    private long suppressAutoStartUntil = 0L;
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
        updateStatsLine("fps in/out: - | drops: - | reconnects: -");
        updateStatus(STATE_IDLE, "tap Settings -> Start Live", 0);
        startStatusPolling();
    }

    @Override
    protected void onDestroy() {
        stopStatusPolling();
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
        debugControlsRow = findViewById(R.id.debugControlsRow);
        statusLed = findViewById(R.id.statusLed);
        cursorOverlay = findViewById(R.id.cursorOverlay);

        statusText = findViewById(R.id.statusText);
        detailText = findViewById(R.id.detailText);
        bpsText = findViewById(R.id.bpsText);
        statsText = findViewById(R.id.statsText);
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
                updateStatus(STATE_IDLE, "surface ready", 0);
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                surface = holder.getSurface();
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                stopLiveView();
                surface = null;
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
        daemonService = health != null ? health.optString("service", daemonService) : daemonService;

        if (!wasReachable) {
            Toast.makeText(this, "Connected to " + daemonHostName, Toast.LENGTH_SHORT).show();
            appendLiveLogInfo("connected to host " + daemonHostName);
        }

        updateHostHint();
        refreshStatusText();

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
            conn.setRequestProperty("Accept", "application/json");

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
        updateStatsLine("fps in/out: - | drops: - | reconnects: -");
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
                ? "fps in/out: - | drops: - | reconnects: -"
                : line);
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
                    statusListener.onStats("fps in/out: - | drops: " + droppedTotal + " | reconnects: " + reconnects);

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
                        Log.e(TAG, "stream worker failed", e);
                        statusListener.onStatus(STATE_ERROR, "stream error: " + e.getClass().getSimpleName(), 0);
                        statusListener.onStats("fps in/out: - | drops: " + droppedTotal + " | reconnects: " + reconnects);
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
            byte[] readBuf = new byte[64 * 1024];
            byte[] streamBuf = new byte[2 * 1024 * 1024];
            int streamLen = 0;
            int streamMode = -1; // -1 unknown, 0 annexb, 1 avcc

            long frames = 0;
            long bytes = 0;
            long inFrames = 0;
            long outFrames = 0;
            long droppedSec = 0;
            long lastLog = SystemClock.elapsedRealtime();

            while (running) {
                int count = input.read(readBuf);
                if (count < 0) {
                    throw new IOException("stream closed");
                }
                if (count == 0) {
                    continue;
                }

                if (streamLen + count > streamBuf.length) {
                    int newLen = Math.max(streamBuf.length * 2, streamLen + count);
                    byte[] bigger = new byte[newLen];
                    System.arraycopy(streamBuf, 0, bigger, 0, streamLen);
                    streamBuf = bigger;
                }
                System.arraycopy(readBuf, 0, streamBuf, streamLen, count);
                streamLen += count;
                bytes += count;

                if (streamMode < 0 && streamLen >= 8) {
                    int start = findStartCode(streamBuf, 0, Math.min(streamLen, 128));
                    streamMode = start >= 0 ? 0 : 1;
                }

                if (streamMode == 1) {
                    while (streamLen >= 4) {
                        int nalSize =
                                ((streamBuf[0] & 0xFF) << 24) |
                                        ((streamBuf[1] & 0xFF) << 16) |
                                        ((streamBuf[2] & 0xFF) << 8) |
                                        (streamBuf[3] & 0xFF);

                        if (nalSize <= 0 || nalSize > (4 * 1024 * 1024)) {
                            System.arraycopy(streamBuf, 1, streamBuf, 0, streamLen - 1);
                            streamLen -= 1;
                            droppedSec++;
                            continue;
                        }
                        if (streamLen < 4 + nalSize) {
                            break;
                        }

                        if (queueNal(codec, streamBuf, 4, nalSize, frames * frameUs)) {
                            inFrames++;
                        } else {
                            droppedSec++;
                        }
                        frames++;
                        outFrames += drain(codec);

                        int consumed = 4 + nalSize;
                        if (streamLen - consumed > 0) {
                            System.arraycopy(streamBuf, consumed, streamBuf, 0, streamLen - consumed);
                        }
                        streamLen -= consumed;
                    }
                } else {
                    int nalStart = findStartCode(streamBuf, 0, streamLen);
                    if (nalStart > 0) {
                        System.arraycopy(streamBuf, nalStart, streamBuf, 0, streamLen - nalStart);
                        streamLen -= nalStart;
                        nalStart = 0;
                    }

                    while (true) {
                        int next = findStartCode(streamBuf, nalStart + 3, streamLen);
                        if (nalStart < 0 || next < 0) {
                            break;
                        }

                        int nalSize = next - nalStart;
                        if (nalSize > 0) {
                            if (queueNal(codec, streamBuf, nalStart, nalSize, frames * frameUs)) {
                                inFrames++;
                            } else {
                                droppedSec++;
                            }
                            frames++;
                            outFrames += drain(codec);
                        }
                        nalStart = next;
                    }

                    if (nalStart > 0) {
                        System.arraycopy(streamBuf, nalStart, streamBuf, 0, streamLen - nalStart);
                        streamLen -= nalStart;
                    }
                }

                long now = SystemClock.elapsedRealtime();
                if (now - lastLog >= 1000) {
                    droppedTotal += droppedSec;
                    reconnectDelayMs = 800;
                    statusListener.onStatus(STATE_STREAMING, "rendering live desktop", bytes);
                    statusListener.onStats(
                            "fps in/out: " + inFrames + "/" + outFrames
                                    + " | drops: " + droppedTotal
                                    + " | reconnects: " + reconnects
                    );
                    bytes = 0;
                    inFrames = 0;
                    outFrames = 0;
                    droppedSec = 0;
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

        private static long drain(MediaCodec codec) {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long released = 0;
            while (true) {
                int outputIndex = codec.dequeueOutputBuffer(info, 0);
                if (outputIndex >= 0) {
                    boolean render = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0;
                    codec.releaseOutputBuffer(outputIndex, render);
                    released++;
                    continue;
                }
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER ||
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    break;
                }
                break;
            }
            return released;
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
    }
}
