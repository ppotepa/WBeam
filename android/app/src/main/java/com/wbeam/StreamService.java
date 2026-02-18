package com.wbeam;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.InputStream;
import java.net.Socket;

public class StreamService extends Service {
    public static final String ACTION_START = "com.wbeam.action.START";
    public static final String ACTION_STOP = "com.wbeam.action.STOP";

    private static final String TAG = "WBeamService";
    private static final String CHANNEL_ID = "wbeam_stream";

    private volatile boolean running;
    private Thread worker;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(1, buildNotification("connecting to 127.0.0.1:5000"));
        startWorker();
        return START_STICKY;
    }

    private void startWorker() {
        if (worker != null && worker.isAlive()) {
            return;
        }

        running = true;
        worker = new Thread(() -> {
            try (Socket socket = new Socket("127.0.0.1", 5000);
                 InputStream input = socket.getInputStream()) {
                Log.i(TAG, "connected to stream");

                byte[] buffer = new byte[64 * 1024];
                long bytes = 0;
                long lastLog = System.currentTimeMillis();

                while (running) {
                    int read = input.read(buffer);
                    if (read < 0) {
                        break;
                    }
                    bytes += read;

                    long now = System.currentTimeMillis();
                    if (now - lastLog >= 1000) {
                        Log.i(TAG, "receiving h264 bytes/s ~ " + bytes);
                        bytes = 0;
                        lastLog = now;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "stream worker failed", e);
            } finally {
                stopSelf();
            }
        });
        worker.setName("wbeam-stream-worker");
        worker.start();
    }

    @Override
    public void onDestroy() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
        super.onDestroy();
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
}
