package com.proto.demo;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.Window;
import android.view.WindowManager;

import com.proto.demo.config.StreamConfig;
import com.proto.demo.pipeline.FrameMailbox;
import com.proto.demo.rendering.JavaRenderer;
import com.proto.demo.rendering.NativeRenderer;
import com.proto.demo.rendering.RendererChain;
import com.proto.demo.rendering.RenderLoop;
import com.proto.demo.transport.AdbPushTransport;
import com.proto.demo.transport.H264Transport;
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

    private Transport  activeTransport;
    private RenderLoop renderLoop;
    private Thread     ioThread;
    private Thread     renderThread;

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        config = new StreamConfig(getIntent());
        layout = new ScreenLayout(this);
        status = new StatusUpdater(layout.statusView);
        NativeRenderer nativeRenderer = new NativeRenderer();
        if (config.forceJavaFallback) {
            nativeRenderer.disable();
        }
        renderer = new RendererChain(
            nativeRenderer,
            new JavaRenderer(layout.surfaceView.getHolder()));

        setContentView(layout.root);
        layout.surfaceView.getHolder().addCallback(this);

        if (config.useAdbPush && config.useH264) {
            status.set("WBeam — H264 surface loading…");
        } else {
            status.set("WBeam — surface loading… ("
                + (renderer.isNativeActive() ? "turbo" : "java") + ")");
        }
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
        // surfaceChanged is always called immediately after surfaceCreated with actual dims
        // so no need to start IO here — we do it in surfaceChanged.
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int fmt, int w, int h) {
        if (!(config.useAdbPush && config.useH264)) {
            renderer.onSurfaceChanged(holder.getSurface(), w, h);
        }
        // Start (or restart) IO now that we know the real surface dimensions
        if (ioThread == null || !ioThread.isAlive()) {
            startIO();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        stopIO();
        if (!(config.useAdbPush && config.useH264)) {
            renderer.onSurfaceDestroyed();
        }
    }

    // ── IO control ────────────────────────────────────────────────────────────
    private void startIO() {
        stopIO();

        if (config.useAdbPush && config.useH264) {
            // H264 goes straight into MediaCodec->Surface, bypassing JPEG decode/render loop.
            activeTransport = new H264Transport(
                status,
                layout.surfaceView.getHolder().getSurface(),
                config.captureSize,
                config.h264Reorder);
        } else {
            String label = config.useAdbPush ? "ADB" : "HTTP";

            // Mailbox decouples IO (producer) from render (consumer).
            // Frame buffers are recycled to avoid GC.  Warm capacity = typical JPEG size.
            FrameMailbox mailbox = new FrameMailbox(64 * 1024);

            // Render loop — dedicated high-priority thread, drains mailbox as fast as possible
            renderLoop   = new RenderLoop(mailbox, renderer, status, label);
            renderThread = new Thread(renderLoop, "wbeam-render");
            renderThread.start();

            // IO listener: copy frame into pool buffer, publish to mailbox (non-blocking).
            // arraycopy of ~30 KB takes ~100 ns — IO thread never blocks on render latency.
            if (config.useAdbPush) {
                activeTransport = new AdbPushTransport(
                    (data, len) -> {
                        FrameMailbox.Frame buf = mailbox.acquire(len);
                        System.arraycopy(data, 0, buf.data, 0, len);
                        buf.len = len;
                        mailbox.publish(buf);
                    },
                    status);
            } else {
                activeTransport = new HttpMjpegTransport(
                    (data, len) -> {
                        FrameMailbox.Frame buf = mailbox.acquire(len);
                        System.arraycopy(data, 0, buf.data, 0, len);
                        buf.len = len;
                        mailbox.publish(buf);
                    },
                    status,
                    config.hostIp);
            }
        }

        ioThread = new Thread(activeTransport, "wbeam-io");
        ioThread.start();
    }

    private void stopIO() {
        // Stop transport first so IO thread stops producing
        if (activeTransport != null) {
            activeTransport.stop();
            activeTransport = null;
        }
        if (ioThread != null) {
            ioThread.interrupt();
            try { ioThread.join(2000); } catch (InterruptedException ignored) {}
            ioThread = null;
        }
        // Stop render loop after IO so it can drain any last frame in the mailbox
        if (renderLoop != null) {
            renderLoop.stop();
            renderLoop = null;
        }
        if (renderThread != null) {
            renderThread.interrupt();
            try { renderThread.join(2000); } catch (InterruptedException ignored) {}
            renderThread = null;
        }
    }
}
