package com.proto.demo;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;

/**
 * WBeam proto — dead-simple USB second-screen receiver.
 *
 * Transport A (default):  ADB push  — host pushes WBJ1-framed JPEGs to port 5006
 * Transport B:            HTTP MJPEG — pull from http://<host_ip>:5005/mjpeg
 *
 * Intent extras (passed by run.sh / rr):
 *   --es host_ip  <ip>          host machine IP (needed for HTTP mode)
 *   --ez adb_push true|false    true = ADB push (default), false = HTTP MJPEG
 *
 * Rendering: libturbojpeg (native, fast) → BitmapFactory/Canvas (fallback)
 */
public class MainActivity extends Activity implements SurfaceHolder.Callback {

    private static final String TAG   = "WBeam";
    private static final int ADB_PORT = 5006;
    private static final int OUT_W    = 1920;
    private static final int OUT_H    = 1080;
    private static final int MAX_FRAME = 12 * 1024 * 1024; // 12 MB safety cap

    // ── Native JNI (bound to com.proto.demo.MainActivity.native*) ────────────
    static {
        try { System.loadLibrary("turbojpeg"); } // must load before wbeam
        catch (UnsatisfiedLinkError ignored) {}
        try { System.loadLibrary("wbeam"); }
        catch (UnsatisfiedLinkError e) { Log.w(TAG, "wbeam native lib not found: " + e.getMessage()); }
    }
    private static native long    nativeCreate(int outMaxW, int outMaxH);
    private static native void    nativeDestroy(long handle);
    private static native void    nativeSetSurface(long handle, Surface surface);
    private static native void    nativeClearSurface(long handle);
    private static native boolean nativeTurboAvailable();
    private static native String  nativeGetDiag(long handle);
    private static native int     nativeDecodeAndRender(long handle, byte[] jpegArr, int len, int outMaxW, int outMaxH);

    // ── State ─────────────────────────────────────────────────────────────────
    private SurfaceView  surfaceView;
    private TextView     statusView;
    private Surface      surface;
    private long         nativeHandle = 0;
    private boolean      useNative    = false;   // true = turbo+ANativeWindow path
    private boolean      useAdbPush   = true;
    private String       hostIp       = "192.168.42.129";

    private volatile boolean running = false;
    private Thread           ioThread;

    // stats
    private long frameCount = 0;
    private long lastStatTime = 0;

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN |
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Intent parameters from run.sh
        Intent intent = getIntent();
        String ip = intent.getStringExtra("host_ip");
        if (ip != null && !ip.trim().isEmpty()) hostIp = ip.trim();
        if (intent.hasExtra("adb_push"))
            useAdbPush = intent.getBooleanExtra("adb_push", true);

        // Minimalist layout: fullscreen SurfaceView + tiny status overlay
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surfaceView = new SurfaceView(this);
        root.addView(surfaceView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT));

        statusView = new TextView(this);
        statusView.setTextColor(Color.WHITE);
        statusView.setTextSize(11f);
        statusView.setPadding(10, 6, 10, 6);
        statusView.setShadowLayer(3f, 1f, 1f, Color.BLACK);
        statusView.setBackgroundColor(0x88000000);
        root.addView(statusView, new FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);
        surfaceView.getHolder().addCallback(this);

        // Init native
        try {
            useNative = nativeTurboAvailable();
        } catch (UnsatisfiedLinkError e) {
            useNative = false;
        }
        if (useNative) {
            try {
                nativeHandle = nativeCreate(OUT_W, OUT_H);
                if (nativeHandle == 0) useNative = false;
            } catch (UnsatisfiedLinkError e) {
                useNative = false;
            }
        }

        setStatus("WBeam — surface loading… (" + (useNative ? "turbo" : "java") + ")");
        Log.i(TAG, "onCreate host=" + hostIp + " adbPush=" + useAdbPush + " native=" + useNative);
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        stopIO();
        if (nativeHandle != 0) {
            try { nativeDestroy(nativeHandle); } catch (UnsatisfiedLinkError ignored) {}
            nativeHandle = 0;
        }
    }

    // ── SurfaceHolder.Callback ────────────────────────────────────────────────
    @Override public void surfaceCreated(SurfaceHolder holder) {
        surface = holder.getSurface();
        if (useNative && nativeHandle != 0) {
            try { nativeSetSurface(nativeHandle, surface); }
            catch (UnsatisfiedLinkError ignored) {}
        }
        startIO();
    }

    @Override public void surfaceChanged(SurfaceHolder holder, int fmt, int w, int h) {
        surface = holder.getSurface();
        if (useNative && nativeHandle != 0) {
            try { nativeSetSurface(nativeHandle, surface); }
            catch (UnsatisfiedLinkError ignored) {}
        }
    }

    @Override public void surfaceDestroyed(SurfaceHolder holder) {
        stopIO();
        if (nativeHandle != 0) {
            try { nativeClearSurface(nativeHandle); } catch (UnsatisfiedLinkError ignored) {}
        }
        surface = null;
    }

    // ── IO control ────────────────────────────────────────────────────────────
    private void startIO() {
        stopIO();
        running  = true;
        frameCount = 0;
        lastStatTime = System.currentTimeMillis();
        ioThread = new Thread(useAdbPush ? this::runAdbPush : this::runHttpMjpeg, "wbeam-io");
        ioThread.start();
    }

    private void stopIO() {
        running = false;
        if (ioThread != null) {
            ioThread.interrupt();
            try { ioThread.join(2000); } catch (InterruptedException ignored) {}
            ioThread = null;
        }
    }

    // ── ADB push receiver ─────────────────────────────────────────────────────
    // Protocol: magic(4) + seq(8) + ts_ms(8) + len(4) + payload(len)
    // magic = "WBJ1" or "WBH1"  (both currently JPEG payload)
    private void runAdbPush() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        setStatus("ADB: listening on :" + ADB_PORT);
        // Retry bind — previous process may still hold the port for a moment.
        // Worst case: adbd TCP keepalive timeout is ~45s, so retry for 70s.
        ServerSocket ss = null;
        for (int attempt = 0; attempt < 70 && running; attempt++) {
            try {
                ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(ADB_PORT));
                break; // success
            } catch (IOException e) {
                try { if (ss != null) ss.close(); } catch (IOException ignored) {}
                ss = null;
                int secsLeft = 70 - attempt;
                setStatus("ADB: port busy (" + secsLeft + "s) " + e.getMessage());
                sleep(1000);
            }
        }
        if (ss == null) { setStatus("ADB: could not bind port " + ADB_PORT); return; }
        try (ServerSocket server = ss) {
            while (running && !Thread.currentThread().isInterrupted()) {
                Socket client;
                try { client = server.accept(); }
                catch (IOException e) { if (running) { sleep(100); } continue; }

                setStatus("ADB: client connected — streaming");
                try {
                    client.setTcpNoDelay(true);
                    receiveWbj1(new BufferedInputStream(client.getInputStream(), 256 * 1024));
                } catch (IOException e) {
                    if (running) setStatus("ADB: lost connection — waiting…");
                } finally {
                    try { client.close(); } catch (IOException ignored) {}
                }
                sleep(150);
            }
        } catch (IOException e) {
            if (running) setStatus("ADB: error — " + e.getMessage());
        }
    }

    private void receiveWbj1(InputStream in) throws IOException {
        byte[] magic  = new byte[4];
        byte[] header = new byte[20]; // seq(8) + ts_ms(8) + len(4)
        byte[] buf    = new byte[MAX_FRAME];

        while (running && !Thread.currentThread().isInterrupted()) {
            readFully(in, magic,  4);
            if (!(magic[0]=='W' && magic[1]=='B' && magic[3]=='1'))
                throw new IOException("bad magic: " + (char)magic[0]+(char)magic[1]+(char)magic[2]+(char)magic[3]);

            readFully(in, header, 20);
            int len = u32be(header, 16);
            if (len <= 0 || len > MAX_FRAME) throw new IOException("bad len=" + len);

            if (buf.length < len) buf = new byte[len];
            readFully(in, buf, len);

            renderJpeg(buf, len);
        }
    }

    // ── HTTP MJPEG client ─────────────────────────────────────────────────────
    private void runHttpMjpeg() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        while (running && !Thread.currentThread().isInterrupted()) {
            String url = "http://" + hostIp + ":5005/mjpeg";
            setStatus("HTTP: connecting to " + url);
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(10000);
                conn.connect();

                String boundary = extractBoundary(conn.getContentType());
                setStatus("HTTP: streaming from " + hostIp);
                receiveMjpeg(new BufferedInputStream(conn.getInputStream(), 128 * 1024), boundary);
                conn.disconnect();
            } catch (Exception e) {
                if (running) {
                    setStatus("HTTP: " + e.getMessage() + " — retrying…");
                    sleep(1000);
                }
            }
        }
    }

    private void receiveMjpeg(InputStream in, String boundary) throws IOException {
        byte[] buf = new byte[MAX_FRAME];
        while (running && !Thread.currentThread().isInterrupted()) {
            // Skip to next part boundary
            String line;
            do {
                line = readLine(in);
                if (line == null) return;
            } while (!line.startsWith("--"));

            // Read part headers
            int len = -1;
            for (;;) {
                String h = readLine(in);
                if (h == null) return;
                if (h.isEmpty()) break;
                String lower = h.toLowerCase();
                if (lower.startsWith("content-length:")) {
                    try { len = Integer.parseInt(lower.substring(15).trim()); }
                    catch (NumberFormatException ignored) {}
                }
            }

            if (len > 0 && len <= MAX_FRAME) {
                if (buf.length < len) buf = new byte[len];
                readFully(in, buf, len);
                renderJpeg(buf, len);
            }
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────
    private void renderJpeg(byte[] data, int len) {
        if (useNative && nativeHandle != 0) {
            try {
                int rc = nativeDecodeAndRender(nativeHandle, data, len, OUT_W, OUT_H);
                if (rc == 0) { onFrameOk(); return; }
                Log.w(TAG, "native rc=" + rc + " (diag:" + nativeGetDiag(nativeHandle) + ")");
                // don't switch to Java — surface ownership is with native
                return;
            } catch (UnsatisfiedLinkError e) {
                useNative = false;
            }
        }
        // Java fallback (BitmapFactory + lockCanvas)
        Surface s = surface;
        if (s == null) return;
        try {
            Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, len);
            if (bmp == null) return;
            Canvas c = surfaceView.getHolder().lockCanvas(null);
            if (c == null) { bmp.recycle(); return; }
            c.drawBitmap(bmp, 0, 0, null);
            surfaceView.getHolder().unlockCanvasAndPost(c);
            bmp.recycle();
            onFrameOk();
        } catch (Exception e) {
            Log.w(TAG, "Java render: " + e.getMessage());
        }
    }

    private void onFrameOk() {
        frameCount++;
        long now = System.currentTimeMillis();
        if (now - lastStatTime >= 2000) {
            double fps = frameCount * 1000.0 / (now - lastStatTime);
            setStatus(String.format("%s  %.1f fps  f=%d",
                useAdbPush ? "ADB" : "HTTP", fps, frameCount));
            frameCount    = 0;
            lastStatTime  = now;
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────
    private static void readFully(InputStream in, byte[] buf, int len) throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) throw new IOException("EOF after " + off + "/" + len + " bytes");
            off += n;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(128);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') return sb.toString().trim();
            if (b != '\r') sb.append((char) b);
        }
        return sb.length() > 0 ? sb.toString().trim() : null;
    }

    private static int u32be(byte[] b, int off) {
        return ((b[off]&0xFF)<<24) | ((b[off+1]&0xFF)<<16) | ((b[off+2]&0xFF)<<8) | (b[off+3]&0xFF);
    }

    private static String extractBoundary(String contentType) {
        if (contentType == null) return "--myboundary";
        for (String part : contentType.split(";")) {
            String s = part.trim();
            if (s.startsWith("boundary=")) return "--" + s.substring(9).trim();
        }
        return "--myboundary";
    }

    private void setStatus(final String msg) {
        Log.i(TAG, msg);
        new Handler(Looper.getMainLooper()).post(() -> {
            if (statusView != null) statusView.setText(msg);
        });
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
