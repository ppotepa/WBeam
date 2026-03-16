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
import android.widget.TextView;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.SeekBar;
import android.widget.Spinner;

import androidx.appcompat.app.AppCompatActivity;

public final class MainActivityUiBinder {
    private static final int SPINNER_DROPDOWN_LAYOUT = android.R.layout.simple_spinner_dropdown_item;
    private static final String PREFERRED_VIDEO_H264 = "h264";
    private static final String PREFERRED_VIDEO_H265 = "H.265";
    private static final String PREFERRED_VIDEO_H264_LABEL = "H.264";
    private static final String RAW_PNG_MODE = "raw-png";
    private static final String LOG_ON = "Log ON";
    private static final String LOG_OFF = "Log";

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

    public static void setupHudWebView(WebView perfHudWebView) {
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
        if (profileSpinner == null || encoderSpinner == null || cursorSpinner == null) {
            return;
        }
        profileSpinner.setAdapter(new ArrayAdapter<>(
                activity, SPINNER_DROPDOWN_LAYOUT, profileOptions));
        encoderSpinner.setAdapter(new ArrayAdapter<>(
                activity, SPINNER_DROPDOWN_LAYOUT, encoderOptions));
        cursorSpinner.setAdapter(new ArrayAdapter<>(
                activity, SPINNER_DROPDOWN_LAYOUT, cursorOptions));

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
        if (resolutionSeek == null || fpsSeek == null || bitrateSeek == null) {
            return;
        }
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
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN
                        || action == MotionEvent.ACTION_MOVE
                        || action == MotionEvent.ACTION_UP) {
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
            simpleModePreferredButton.setText(PREFERRED_VIDEO_H264.equals(preferredVideo) ? PREFERRED_VIDEO_H264_LABEL : PREFERRED_VIDEO_H265);
            simpleModePreferredButton.setOnClickListener(v -> callbacks.onSimpleModeSelected(preferredVideo));
        }
        if (simpleModeRawButton != null) {
            simpleModeRawButton.setOnClickListener(v -> callbacks.onSimpleModeSelected(RAW_PNG_MODE));
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
        bindFpsButton(simpleFps30Button, 30, callbacks);
        bindFpsButton(simpleFps45Button, 45, callbacks);
        bindFpsButton(simpleFps60Button, 60, callbacks);
        bindFpsButton(simpleFps90Button, 90, callbacks);
        bindFpsButton(simpleFps120Button, 120, callbacks);
        bindFpsButton(simpleFps144Button, 144, callbacks);
    }

    private static void bindFpsButton(Button button, int fps, FpsSelectionCallbacks callbacks) {
        if (button != null) {
            button.setOnClickListener(v -> callbacks.onFpsSelected(fps));
        }
    }

    public static void bindSimpleApplyButton(Button simpleApplyButton, Runnable onApply) {
        if (simpleApplyButton != null) {
            simpleApplyButton.setOnClickListener(v -> onApply.run());
        }
    }

    public static void applyActionButtonsEnabled(boolean enabled, Button... buttons) {
        for (Button button : buttons) {
            if (button == null) {
                continue;
            }
            button.setEnabled(enabled);
            button.setAlpha(enabled ? 1.0f : 0.45f);
        }
    }

    public static void applyDebugControlsVisible(boolean visible, View debugControlsRow, Button testButton) {
        int visibility = visible ? View.VISIBLE : View.GONE;
        if (debugControlsRow != null) {
            debugControlsRow.setVisibility(visibility);
        }
        if (testButton != null) {
            testButton.setVisibility(visibility);
        }
    }

    public static boolean applyLiveLogPanelState(
            boolean currentlyVisible,
            TextView liveLogText,
            Button logButton
    ) {
        boolean nextVisible = !currentlyVisible;
        if (liveLogText != null) {
            liveLogText.setVisibility(nextVisible ? View.VISIBLE : View.GONE);
        }
        if (logButton != null) {
            logButton.setText(nextVisible ? LOG_ON : LOG_OFF);
        }
        return nextVisible;
    }

    public static void applyVisibility(int visibility, View... views) {
        for (View view : views) {
            if (view != null) {
                view.setVisibility(visibility);
            }
        }
    }
}
