package com.wbeam.ui;

import android.graphics.Color;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.widget.Spinner;

import androidx.appcompat.app.AppCompatActivity;

public final class MainActivityUiBinder {
    public interface SimpleModeCallbacks {
        void onSimpleModeSelected(String mode);
    }

    public interface FpsSelectionCallbacks {
        void onFpsSelected(int fps);
    }

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

    public static void bindSettingsCloseButton(Button settingsCloseButton, Runnable onClose) {
        if (settingsCloseButton != null) {
            settingsCloseButton.setOnClickListener(v -> onClose.run());
        }
    }

    public static void bindSimpleMenuTouchRefresh(View simpleMenuPanel, Runnable onRefreshAutoHide) {
        if (simpleMenuPanel != null) {
            simpleMenuPanel.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN
                        || event.getActionMasked() == MotionEvent.ACTION_MOVE
                        || event.getActionMasked() == MotionEvent.ACTION_UP) {
                    onRefreshAutoHide.run();
                }
                return false;
            });
        }
    }

    public static void bindDebugInfoTouchFade(
            View debugInfoPanel,
            Handler uiHandler,
            Runnable fadeTask,
            float touchAlpha,
            long resetDelayMs
    ) {
        if (debugInfoPanel != null) {
            debugInfoPanel.setOnTouchListener((v, event) -> {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN
                        || action == MotionEvent.ACTION_MOVE
                        || action == MotionEvent.ACTION_UP) {
                    debugInfoPanel.setAlpha(touchAlpha);
                    uiHandler.removeCallbacks(fadeTask);
                    uiHandler.postDelayed(fadeTask, resetDelayMs);
                }
                return false;
            });
        }
    }

    public static void bindSimpleModeButtons(
            Button simpleModePreferredButton,
            Button simpleModeRawButton,
            String preferredVideo,
            SimpleModeCallbacks callbacks
    ) {
        if (simpleModePreferredButton != null) {
            simpleModePreferredButton.setText("h264".equals(preferredVideo) ? "H.264" : "H.265");
            simpleModePreferredButton.setOnClickListener(v -> callbacks.onSimpleModeSelected(preferredVideo));
        }
        if (simpleModeRawButton != null) {
            simpleModeRawButton.setOnClickListener(v -> callbacks.onSimpleModeSelected("raw-png"));
        }
    }

    public static void bindSimpleFpsButtons(
            Button simpleFps30Button,
            Button simpleFps45Button,
            Button simpleFps60Button,
            Button simpleFps90Button,
            Button simpleFps120Button,
            Button simpleFps144Button,
            FpsSelectionCallbacks callbacks
    ) {
        if (simpleFps30Button != null) simpleFps30Button.setOnClickListener(v -> callbacks.onFpsSelected(30));
        if (simpleFps45Button != null) simpleFps45Button.setOnClickListener(v -> callbacks.onFpsSelected(45));
        if (simpleFps60Button != null) simpleFps60Button.setOnClickListener(v -> callbacks.onFpsSelected(60));
        if (simpleFps90Button != null) simpleFps90Button.setOnClickListener(v -> callbacks.onFpsSelected(90));
        if (simpleFps120Button != null) simpleFps120Button.setOnClickListener(v -> callbacks.onFpsSelected(120));
        if (simpleFps144Button != null) simpleFps144Button.setOnClickListener(v -> callbacks.onFpsSelected(144));
    }

    public static void bindSimpleApplyButton(Button simpleApplyButton, Runnable onApply) {
        if (simpleApplyButton != null) {
            simpleApplyButton.setOnClickListener(v -> onApply.run());
        }
    }
}
