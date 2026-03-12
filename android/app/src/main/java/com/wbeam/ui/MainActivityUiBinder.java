package com.wbeam.ui;

import android.graphics.Color;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.widget.Spinner;

import androidx.appcompat.app.AppCompatActivity;

public final class MainActivityUiBinder {
    public interface SurfaceCallbacks {
        void onSurfaceCreated(Surface surface, boolean ready);
        void onSurfaceChanged(Surface surface, boolean ready);
        void onSurfaceDestroyed();
        boolean isCursorOverlayEnabled();
        void onCursorOverlayMotion(float x, float y, int actionMasked);
    }

    private MainActivityUiBinder() {
    }

    public static void setupTrainerHudWebView(WebView perfHudWebView) {
        if (perfHudWebView == null) {
            return;
        }
        WebSettings settings = perfHudWebView.getSettings();
        settings.setJavaScriptEnabled(false);
        settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(false);
        settings.setLoadWithOverviewMode(false);
        perfHudWebView.setBackgroundColor(Color.TRANSPARENT);
        perfHudWebView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        perfHudWebView.setVerticalScrollBarEnabled(false);
        perfHudWebView.setHorizontalScrollBarEnabled(false);
        perfHudWebView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        perfHudWebView.setPadding(0, 0, 0, 0);
        perfHudWebView.setInitialScale(100);
    }

    public static void setupSpinners(
            AppCompatActivity activity,
            Spinner profileSpinner,
            Spinner encoderSpinner,
            Spinner cursorSpinner,
            String[] profileOptions,
            String[] encoderOptions,
            String[] cursorOptions,
            Runnable onEncoderSelectionChanged,
            Runnable onCursorSelectionChanged
    ) {
        profileSpinner.setAdapter(new ArrayAdapter<>(
                activity, android.R.layout.simple_spinner_dropdown_item, profileOptions));
        encoderSpinner.setAdapter(new ArrayAdapter<>(
                activity, android.R.layout.simple_spinner_dropdown_item, encoderOptions));
        cursorSpinner.setAdapter(new ArrayAdapter<>(
                activity, android.R.layout.simple_spinner_dropdown_item, cursorOptions));

        encoderSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                onEncoderSelectionChanged.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        cursorSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                onCursorSelectionChanged.run();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    public static void setupSeekbars(
            SeekBar resolutionSeek,
            SeekBar fpsSeek,
            SeekBar bitrateSeek,
            Runnable onValuesChanged
    ) {
        resolutionSeek.setMax(50); // 50..100%
        fpsSeek.setMax(120); // 24..144 fps
        bitrateSeek.setMax(295); // 5..300 Mbps

        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                onValuesChanged.run();
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

    public static void setupSurfaceCallbacks(SurfaceView preview, SurfaceCallbacks callbacks) {
        preview.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                Surface surface = holder.getSurface();
                callbacks.onSurfaceCreated(surface, surface != null && surface.isValid());
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                Surface surface = holder.getSurface();
                callbacks.onSurfaceChanged(surface, surface != null && surface.isValid());
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                callbacks.onSurfaceDestroyed();
            }
        });

        preview.setOnTouchListener((v, event) -> {
            if (!callbacks.isCursorOverlayEnabled()) {
                return false;
            }
            callbacks.onCursorOverlayMotion(event.getX(), event.getY(), event.getActionMasked());
            return false;
        });

        preview.setOnGenericMotionListener((v, event) -> {
            if (!callbacks.isCursorOverlayEnabled()) {
                return false;
            }
            if (event.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE
                    || event.getActionMasked() == MotionEvent.ACTION_MOVE) {
                callbacks.onCursorOverlayMotion(event.getX(), event.getY(), event.getActionMasked());
            }
            return false;
        });
    }
}
