package com.proto.demo;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.Window;
import android.view.WindowManager;

import com.proto.demo.config.StreamConfig;
import com.proto.demo.rendering.JavaRenderer;
import com.proto.demo.rendering.NativeRenderer;
import com.proto.demo.rendering.RendererChain;
import com.proto.demo.stats.FrameStats;
import com.proto.demo.transport.AdbPushTransport;
import com.proto.demo.transport.HttpMjpegTransport;
import com.proto.demo.transport.Transport;
import com.proto.demo.ui.ScreenLayout;
import com.proto.demo.ui.StatusUpdater;

/**
 * WBeam proto — USB second-screen receiver.
 *
 * Single responsibility: Android lifecycle host and wiring point (Coordinator).
 * All domain logic is delegated to dedicated single-responsibility classes:
 *
 *   StreamConfig       — Intent / constant parsing
 *   NativeBridge       — JNI declarations
 *   AdbPushTransport   — WBJ1 frame intake over ADB forward
 *   HttpMjpegTransport — MJPEG pull over HTTP
 *   NativeRenderer     — libturbojpeg → ANativeWindow
 *   JavaRenderer       — BitmapFactory → lockCanvas fallback
 *   RendererChain      — picks native or Java path
 *   FrameStats         — FPS counting
 *   StatusUpdater      — UI thread text update
 *   ScreenLayout       — SurfaceView + overlay layout
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG = "WBeam";

    // ── Domain objects ────────────────────────────────────────────────────────
    private StreamConfig  config;
    private ScreenLayout  layout;
    private StatusUpdater status;
    private RendererChain renderer;

    private Transport activeTransport;
    private Thread    ioThread;

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        config   = new StreamConfig(getIntent());
        layout   = new ScreenLayout(this);
        status   = new StatusUpdater(layout.statusView);
        renderer = new RendererChain(
            new NativeRenderer(),
            new JavaRenderer(layout.surfaceView.getHolder()));

        setContentView(layout.root);
        layout.surfaceView.getHolder().addCallback(this);

        status.set("WBeam — surface loading… ("
            + (renderer.isNativeActive() ? "turbo" : "java") + ")");
        Log.i(TAG, "onCreate " + config);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopIO();
        renderer.release();
    }

    // ── SurfaceHolder.Callback ────────────────────────────────────────────────
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        renderer.onSurfaceChanged(holder.getSurface());
        startIO();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int fmt, int w, int h) {
        renderer.onSurfaceChanged(holder.getSurface());
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopIO();
        renderer.onSurfaceDestroyed();
    }

    // ── IO control ────────────────────────────────────────────────────────────
    private void startIO() {
        stopIO();

        FrameStats stats = new FrameStats(
            config.useAdbPush ? "ADB" : "HTTP",
            (fps, total, transport) ->
                status.set(String.format("%s  %.1f fps  f=%d", transport, fps, total)));

        if (config.useAdbPush) {
            AdbPushTransport adb = new AdbPushTransport(
                (data, len) -> { renderer.render(data, len); stats.onFrame(); },
                status);
            activeTransport = adb;
        } else {
            HttpMjpegTransport http = new HttpMjpegTransport(
                (data, len) -> { renderer.render(data, len); stats.onFrame(); },
                status,
                config.hostIp);
            activeTransport = http;
        }

        ioThread = new Thread(activeTransport, "wbeam-io");
        ioThread.start();
    }

    private void stopIO() {
        if (activeTransport != null) {
            activeTransport.stop();
            activeTransport = null;
        }
        if (ioThread != null) {
            ioThread.interrupt();
            try { ioThread.join(2000); } catch (InterruptedException ignored) {}
            ioThread = null;
        }
    }
}
