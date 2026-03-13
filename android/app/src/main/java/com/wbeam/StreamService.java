package com.wbeam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.InputStream;
import java.net.Socket;

public class StreamService extends Service {
    public static final String ACTION_START = "com.wbeam.action.START";
    public static final String ACTION_STOP = "com.wbeam.action.STOP";
    public static final String ACTION_STATUS = "com.wbeam.action.STATUS";

    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_INFO = "info";
    public static final String EXTRA_BPS = "bps";

    public static final String STATE_IDLE = "idle";
    public static final String STATE_CONNECTING = "connecting";
    public static final String STATE_STREAMING = "streaming";
    public static final String STATE_ERROR = "error";

    private static final String TAG = "WBeamService";
    private static final String CHANNEL_ID = "wbeam_stream";
    private static final String HOST = resolveHost();
    private static final int STREAM_PORT = BuildConfig.WBEAM_STREAM_PORT;

    private volatile boolean running;
    private Thread worker;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            running = false;
            emitStatus(STATE_IDLE, "stopped by user", 0);
            stopSelf();
            return START_NOT_STICKY;
        }

        emitStatus(STATE_CONNECTING, "opening usb tunnel stream", 0);
        try {
            startForeground(1, buildNotification("connecting to " + HOST + ":" + STREAM_PORT));
        } catch (Exception e) {
            Log.e(TAG, "failed to enter foreground", e);
            emitStatus(STATE_ERROR, "foreground start failed: " + e.getClass().getSimpleName(), 0);
            stopSelf();
            return START_NOT_STICKY;
        }
        startWorker();
        return START_STICKY;
    }

    private void startWorker() {
        if (worker != null && worker.isAlive()) {
            emitStatus(STATE_STREAMING, "worker already running", 0);
            return;
        }

        running = true;
        worker = new Thread(this::runWorkerLoop);
        worker.setName("wbeam-stream-worker");
        worker.start();
    }

    private void runWorkerLoop() {
        while (running) {
            emitStatus(STATE_CONNECTING, "connecting to " + HOST + ":" + STREAM_PORT, 0);
            handleSingleStreamSession();
            if (running) {
                SystemClock.sleep(1000);
            }
        }
    }

    private void handleSingleStreamSession() {
        try (Socket socket = new Socket(HOST, STREAM_PORT);
             InputStream input = socket.getInputStream()) {
            Log.i(TAG, "connected to stream");
            emitStatus(STATE_STREAMING, "connected", 0);
            receiveStreamLoop(input);
        } catch (Exception e) {
            Log.e(TAG, "stream worker failed", e);
            emitStatus(STATE_ERROR, "stream error: " + e.getClass().getSimpleName(), 0);
        }
    }

    private void receiveStreamLoop(InputStream input) throws Exception {
        byte[] buffer = new byte[64 * 1024];
        long bytes = 0;
        long lastLog = System.currentTimeMillis();
        while (running) {
            int read = input.read(buffer);
            if (read < 0) {
                emitStatus(STATE_CONNECTING, "stream ended, reconnecting", 0);
                return;
            }
            bytes += read;
            long now = System.currentTimeMillis();
            if (now - lastLog >= 1000) {
                Log.i(TAG, "receiving h264 bytes/s ~ " + bytes);
                emitStatus(STATE_STREAMING, "receiving stream", bytes);
                bytes = 0;
                lastLog = now;
            }
        }
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
        emitStatus(STATE_IDLE, "service destroyed", 0);
        super.onDestroy();
    }

    private void emitStatus(String state, String info, long bps) {
        Intent status = new Intent(ACTION_STATUS);
        status.setPackage(getPackageName());
        status.putExtra(EXTRA_STATE, state);
        status.putExtra(EXTRA_INFO, info);
        status.putExtra(EXTRA_BPS, bps);
        sendBroadcast(status);
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "WBeam Stream",
                    NotificationManager.IMPORTANCE_LOW
            );
            nm.createNotificationChannel(channel);
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("WBeam")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private static String resolveHost() {
        String configured = BuildConfig.WBEAM_STREAM_HOST;
        if (configured == null || configured.trim().isEmpty()) {
            configured = BuildConfig.WBEAM_HOST;
        }
        if (configured == null) {
            return "127.0.0.1";
        }
        String trimmed = configured.trim();
        return trimmed.isEmpty() ? "127.0.0.1" : trimmed;
    }
}
