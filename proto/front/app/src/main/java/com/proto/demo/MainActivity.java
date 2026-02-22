package com.proto.demo;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ImageView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends AppCompatActivity {
    private ImageView imageView;
    private static final String HOST_IP = "192.168.42.170";
    private static final int TARGET_FPS = 30;
    private static final long FRAME_INTERVAL_MS = 1000L / TARGET_FPS;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean streaming = false;
    private boolean requestInFlight = false;
    private Bitmap lastBitmap;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!streaming) {
                return;
            }

            if (!requestInFlight) {
                requestInFlight = true;
                String url = "http://" + HOST_IP + ":5005/image.jpg?t=" + System.currentTimeMillis();
                new FetchImageTask().execute(url);
            }

            handler.postDelayed(this, FRAME_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        imageView = findViewById(R.id.imageView);
        startStreaming();
    }

    @Override
    protected void onStop() {
        super.onStop();
        stopStreaming();
        if (lastBitmap != null && !lastBitmap.isRecycled()) {
            lastBitmap.recycle();
            lastBitmap = null;
        }
    }

    private void startStreaming() {
        streaming = true;
        requestInFlight = false;
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    private void stopStreaming() {
        streaming = false;
        requestInFlight = false;
        handler.removeCallbacks(tick);
    }

    private class FetchImageTask extends AsyncTask<String, Void, Bitmap> {
        private String error;

        @Override
        protected Bitmap doInBackground(String... strings) {
            String urlString = strings[0];
            HttpURLConnection conn = null;
            InputStream stream = null;
            try {
                URL url = new URL(urlString);
                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(6000);
                conn.setDoInput(true);
                conn.connect();
                stream = conn.getInputStream();
                byte[] data = readAllBytes(stream);
                if (data.length == 0) {
                    error = "empty frame";
                    return null;
                }

                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, bounds);

                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = Bitmap.Config.RGB_565;
                opts.inDither = true;
                opts.inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight);

                return BitmapFactory.decodeByteArray(data, 0, data.length, opts);
            } catch (IOException e) {
                error = e.toString();
                return null;
            } catch (OutOfMemoryError oom) {
                error = "OOM decoding frame";
                return null;
            } finally {
                if (stream != null) {
                    try { stream.close(); } catch (IOException ignored) {}
                }
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            requestInFlight = false;
            if (bitmap != null) {
                Bitmap old = lastBitmap;
                lastBitmap = bitmap;
                imageView.setImageBitmap(bitmap);
                if (old != null && old != bitmap && !old.isRecycled()) {
                    old.recycle();
                }
            }
        }
    }

    private static int computeSampleSize(int width, int height) {
        int maxDim = Math.max(width, height);
        if (maxDim >= 4096) return 4;
        if (maxDim >= 2560) return 2;
        return 1;
    }

    private static byte[] readAllBytes(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = stream.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
