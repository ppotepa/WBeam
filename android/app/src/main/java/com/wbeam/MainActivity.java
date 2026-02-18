package com.wbeam;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "WBeamMain";
    private static final String STATE_IDLE = "idle";
    private static final String STATE_CONNECTING = "connecting";
    private static final String STATE_STREAMING = "streaming";
    private static final String STATE_ERROR = "error";

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5000;
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final long FRAME_US = 1_000_000L / 60L;
    private static final String TEST_VIDEO_URL =
            "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerEscapes.mp4";

    private View statusLed;
    private View rootLayout;
    private View statusPanel;
    private View titleText;
    private View subtitleText;
    private View previewLabel;
    private View modeLabelText;
    private View modeRow;
    private View actionRow;
    private TextView statusText;
    private TextView detailText;
    private TextView bpsText;
    private TextView modeHintText;
    private Button modeAButton;
    private Button modeBButton;
    private Button modeCButton;
    private Button testButton;
    private Button fullscreenButton;
    private boolean isFullscreen = false;
    private String currentMode = "A";

    private Surface surface;
    private H264TcpPlayer player;
    private MediaPlayer mediaPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        rootLayout = findViewById(R.id.rootLayout);
        statusLed = findViewById(R.id.statusLed);
        statusPanel = findViewById(R.id.statusPanel);
        titleText = findViewById(R.id.titleText);
        subtitleText = findViewById(R.id.subtitleText);
        previewLabel = findViewById(R.id.previewLabel);
        modeLabelText = findViewById(R.id.modeLabelText);
        modeRow = findViewById(R.id.modeRow);
        actionRow = findViewById(R.id.actionRow);
        statusText = findViewById(R.id.statusText);
        detailText = findViewById(R.id.detailText);
        bpsText = findViewById(R.id.bpsText);
        modeHintText = findViewById(R.id.modeHintText);

        Button start = findViewById(R.id.startButton);
        Button stop = findViewById(R.id.stopButton);
        testButton = findViewById(R.id.testButton);
        fullscreenButton = findViewById(R.id.fullscreenButton);
        modeAButton = findViewById(R.id.modeAButton);
        modeBButton = findViewById(R.id.modeBButton);
        modeCButton = findViewById(R.id.modeCButton);

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
                updateStatus(STATE_IDLE, "surface destroyed", 0);
            }
        });

        start.setOnClickListener(v -> startLiveView());
        stop.setOnClickListener(v -> stopLiveView());
        testButton.setOnClickListener(v -> startPublicVideoTest());
        fullscreenButton.setOnClickListener(v -> toggleFullscreen());

        modeAButton.setOnClickListener(v -> applyMode("A"));
        modeBButton.setOnClickListener(v -> applyMode("B"));
        modeCButton.setOnClickListener(v -> applyMode("C"));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    1001
            );
        }

        applyMode("A");
        setFullscreen(false);
        updateStatus(STATE_IDLE, "tap Start Live View", 0);
    }

    @Override
    protected void onDestroy() {
        stopLiveView();
        releaseMediaPlayer();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (isFullscreen) {
            setFullscreen(false);
            return;
        }
        super.onBackPressed();
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

        player = new H264TcpPlayer(
                surface,
                (state, info, bps) -> runOnUiThread(() -> updateStatus(state, info, bps))
        );
        player.start();
    }

    private void stopLiveView() {
        if (player != null) {
            player.stop();
            player = null;
        }
        releaseMediaPlayer();
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

    private void applyMode(String mode) {
        currentMode = mode;
        modeAButton.setEnabled(!"A".equals(mode));
        modeBButton.setEnabled(!"B".equals(mode));
        modeCButton.setEnabled(!"C".equals(mode));

        int panelColor;
        if ("A".equals(mode)) {
            panelColor = Color.parseColor("#1E293B");
            detailText.setVisibility(View.VISIBLE);
            bpsText.setVisibility(View.VISIBLE);
            modeHintText.setText("A: balanced panel (state + detail + throughput)");
        } else if ("B".equals(mode)) {
            panelColor = Color.parseColor("#334155");
            detailText.setVisibility(View.GONE);
            bpsText.setVisibility(View.VISIBLE);
            modeHintText.setText("B: compact panel (state + throughput)");
        } else {
            panelColor = Color.parseColor("#0F172A");
            detailText.setVisibility(View.VISIBLE);
            bpsText.setVisibility(View.VISIBLE);
            modeHintText.setText("C: monitor panel (state + detail + throughput, high contrast)");
        }

        if (statusPanel.getBackground() instanceof GradientDrawable) {
            GradientDrawable background = (GradientDrawable) statusPanel.getBackground().mutate();
            background.setColor(panelColor);
        } else {
            statusPanel.setBackgroundColor(panelColor);
        }
    }

    private void toggleFullscreen() {
        setFullscreen(!isFullscreen);
    }

    private void setFullscreen(boolean enable) {
        isFullscreen = enable;

        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());

        if (enable) {
            titleText.setVisibility(View.GONE);
            subtitleText.setVisibility(View.GONE);
            statusPanel.setVisibility(View.GONE);
            modeLabelText.setVisibility(View.GONE);
            modeRow.setVisibility(View.GONE);
            modeHintText.setVisibility(View.GONE);
            actionRow.setVisibility(View.GONE);
            testButton.setVisibility(View.GONE);
            previewLabel.setVisibility(View.GONE);

            if (rootLayout != null) {
                rootLayout.setPadding(0, 0, 0, 0);
            }

            fullscreenButton.setText("Exit Fullscreen");
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            if (controller != null) {
                controller.setSystemBarsBehavior(
                        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
                controller.hide(WindowInsetsCompat.Type.systemBars());
            }
            return;
        }

        titleText.setVisibility(View.VISIBLE);
        subtitleText.setVisibility(View.VISIBLE);
        statusPanel.setVisibility(View.VISIBLE);
        modeLabelText.setVisibility(View.VISIBLE);
        modeRow.setVisibility(View.VISIBLE);
        modeHintText.setVisibility(View.VISIBLE);
        actionRow.setVisibility(View.VISIBLE);
        testButton.setVisibility(View.VISIBLE);
        previewLabel.setVisibility(View.VISIBLE);

        if (rootLayout != null) {
            float density = getResources().getDisplayMetrics().density;
            int padding = (int) (16f * density);
            rootLayout.setPadding(padding, padding, padding, padding);
        }

        fullscreenButton.setText("Fullscreen");
        WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        if (controller != null) {
            controller.show(WindowInsetsCompat.Type.systemBars());
        }

        applyMode(currentMode);
    }

    private void updateStatus(String state, String info, long bps) {
        String normalized = state == null ? STATE_IDLE : state;
        int color = ledColorForState(normalized);
        Log.i(TAG, "status=" + normalized + " info=" + info + " bps=" + bps);

        statusText.setText(normalized.toUpperCase(Locale.US));
        detailText.setText(info == null || info.trim().isEmpty() ? "-" : info);
        bpsText.setText("throughput: " + formatBps(bps));

        if (statusLed.getBackground() instanceof GradientDrawable) {
            GradientDrawable drawable = (GradientDrawable) statusLed.getBackground().mutate();
            drawable.setColor(color);
        } else {
            statusLed.setBackgroundColor(color);
        }
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

    private interface StatusListener {
        void onStatus(String state, String info, long bps);
    }

    private static final class H264TcpPlayer {
        private final Surface surface;
        private final StatusListener statusListener;
        private volatile boolean running;
        private Thread thread;
        private Socket socket;

        H264TcpPlayer(Surface surface, StatusListener statusListener) {
            this.surface = surface;
            this.statusListener = statusListener;
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

                    socket = new Socket();
                    socket.connect(new InetSocketAddress(HOST, PORT), 2000);
                    socket.setTcpNoDelay(true);

                    codec = MediaCodec.createDecoderByType("video/avc");
                    MediaFormat format = MediaFormat.createVideoFormat("video/avc", WIDTH, HEIGHT);
                    codec.configure(format, surface, null, 0);
                    codec.start();

                    statusListener.onStatus(STATE_STREAMING, "connected", 0);
                    decodeLoop(socket.getInputStream(), codec);
                } catch (Exception e) {
                    if (running) {
                        statusListener.onStatus(STATE_ERROR, "stream error: " + e.getClass().getSimpleName(), 0);
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
                    SystemClock.sleep(800);
                }
            }
        }

        private void decodeLoop(InputStream input, MediaCodec codec) throws IOException {
            byte[] readBuf = new byte[64 * 1024];
            byte[] streamBuf = new byte[2 * 1024 * 1024];
            int streamLen = 0;
            long frames = 0;
            long bytes = 0;
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
                        queueNal(codec, streamBuf, nalStart, nalSize, frames * FRAME_US);
                        frames++;
                        drain(codec);
                    }
                    nalStart = next;
                }

                if (nalStart > 0) {
                    System.arraycopy(streamBuf, nalStart, streamBuf, 0, streamLen - nalStart);
                    streamLen -= nalStart;
                }

                long now = SystemClock.elapsedRealtime();
                if (now - lastLog >= 1000) {
                    statusListener.onStatus(STATE_STREAMING, "rendering live desktop", bytes);
                    bytes = 0;
                    lastLog = now;
                }
            }
        }

        private static void queueNal(MediaCodec codec, byte[] data, int offset, int size, long ptsUs) {
            int inputIndex = codec.dequeueInputBuffer(10_000);
            if (inputIndex < 0) {
                return;
            }
            ByteBuffer inputBuffer = codec.getInputBuffer(inputIndex);
            if (inputBuffer == null) {
                codec.queueInputBuffer(inputIndex, 0, 0, ptsUs, 0);
                return;
            }
            inputBuffer.clear();
            if (size > inputBuffer.remaining()) {
                codec.queueInputBuffer(inputIndex, 0, 0, ptsUs, 0);
                return;
            }
            inputBuffer.put(data, offset, size);
            codec.queueInputBuffer(inputIndex, 0, size, ptsUs, 0);
        }

        private static void drain(MediaCodec codec) {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                int outputIndex = codec.dequeueOutputBuffer(info, 0);
                if (outputIndex >= 0) {
                    codec.releaseOutputBuffer(outputIndex, info.size > 0);
                    continue;
                }
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER ||
                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    break;
                }
                break;
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
    }
}
