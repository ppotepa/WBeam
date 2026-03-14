package com.wbeam.ui;

import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.wbeam.R;

public final class MainActivityControlViewsBinder {
    /**
     * Plain data carrier for control views binding.
     */
    public static final class Views {
        private TextView liveLogText;
        private TextView resValueText;
        private TextView fpsValueText;
        private TextView bitrateValueText;
        private TextView hostHintText;
        private Spinner profileSpinner;
        private Spinner encoderSpinner;
        private Spinner cursorSpinner;
        private SeekBar resolutionSeek;
        private SeekBar fpsSeek;
        private SeekBar bitrateSeek;
        private Button settingsButton;
        private Button logButton;
        private Button settingsCloseButton;
        private Button applySettingsButton;
        private Button quickStartButton;
        private Button quickStopButton;
        private Button quickTestButton;
        private Button startButton;
        private Button stopButton;
        private Button testButton;
        private Button fullscreenButton;
        private Button cursorOverlayButton;
        private Button intraOnlyButton;
        private Button simpleModeH265Button;
        private Button simpleModeRawButton;
        private Button simpleFps30Button;
        private Button simpleFps45Button;
        private Button simpleFps60Button;
        private Button simpleFps90Button;
        private Button simpleFps120Button;
        private Button simpleFps144Button;
        private Button simpleApplyButton;

        public TextView getLiveLogText() { return liveLogText; }
        public TextView getResValueText() { return resValueText; }
        public TextView getFpsValueText() { return fpsValueText; }
        public TextView getBitrateValueText() { return bitrateValueText; }
        public TextView getHostHintText() { return hostHintText; }
        public Spinner getProfileSpinner() { return profileSpinner; }
        public Spinner getEncoderSpinner() { return encoderSpinner; }
        public Spinner getCursorSpinner() { return cursorSpinner; }
        public SeekBar getResolutionSeek() { return resolutionSeek; }
        public SeekBar getFpsSeek() { return fpsSeek; }
        public SeekBar getBitrateSeek() { return bitrateSeek; }
        public Button getSettingsButton() { return settingsButton; }
        public Button getLogButton() { return logButton; }
        public Button getSettingsCloseButton() { return settingsCloseButton; }
        public Button getApplySettingsButton() { return applySettingsButton; }
        public Button getQuickStartButton() { return quickStartButton; }
        public Button getQuickStopButton() { return quickStopButton; }
        public Button getQuickTestButton() { return quickTestButton; }
        public Button getStartButton() { return startButton; }
        public Button getStopButton() { return stopButton; }
        public Button getTestButton() { return testButton; }
        public Button getFullscreenButton() { return fullscreenButton; }
        public Button getCursorOverlayButton() { return cursorOverlayButton; }
        public Button getIntraOnlyButton() { return intraOnlyButton; }
        public Button getSimpleModeH265Button() { return simpleModeH265Button; }
        public Button getSimpleModeRawButton() { return simpleModeRawButton; }
        public Button getSimpleFps30Button() { return simpleFps30Button; }
        public Button getSimpleFps45Button() { return simpleFps45Button; }
        public Button getSimpleFps60Button() { return simpleFps60Button; }
        public Button getSimpleFps90Button() { return simpleFps90Button; }
        public Button getSimpleFps120Button() { return simpleFps120Button; }
        public Button getSimpleFps144Button() { return simpleFps144Button; }
        public Button getSimpleApplyButton() { return simpleApplyButton; }
    }

    private MainActivityControlViewsBinder() {
    }

    public static Views bind(AppCompatActivity activity) {
        Views views = new Views();
        views.liveLogText = activity.findViewById(R.id.liveLogText);
        views.resValueText = activity.findViewById(R.id.resValueText);
        views.fpsValueText = activity.findViewById(R.id.fpsValueText);
        views.bitrateValueText = activity.findViewById(R.id.bitrateValueText);
        views.hostHintText = activity.findViewById(R.id.hostHintText);
        views.profileSpinner = activity.findViewById(R.id.profileSpinner);
        views.encoderSpinner = activity.findViewById(R.id.encoderSpinner);
        views.cursorSpinner = activity.findViewById(R.id.cursorSpinner);
        views.resolutionSeek = activity.findViewById(R.id.resolutionSeek);
        views.fpsSeek = activity.findViewById(R.id.fpsSeek);
        views.bitrateSeek = activity.findViewById(R.id.bitrateSeek);
        views.settingsButton = activity.findViewById(R.id.settingsButton);
        views.logButton = activity.findViewById(R.id.logButton);
        views.settingsCloseButton = activity.findViewById(R.id.settingsCloseButton);
        views.applySettingsButton = activity.findViewById(R.id.applySettingsButton);
        views.quickStartButton = activity.findViewById(R.id.quickStartButton);
        views.quickStopButton = activity.findViewById(R.id.quickStopButton);
        views.quickTestButton = activity.findViewById(R.id.quickTestButton);
        views.startButton = activity.findViewById(R.id.startButton);
        views.stopButton = activity.findViewById(R.id.stopButton);
        views.testButton = activity.findViewById(R.id.testButton);
        views.fullscreenButton = activity.findViewById(R.id.fullscreenButton);
        views.cursorOverlayButton = activity.findViewById(R.id.cursorOverlayButton);
        views.intraOnlyButton = activity.findViewById(R.id.intraOnlyButton);
        views.simpleModeH265Button = activity.findViewById(R.id.simpleModeH265Button);
        views.simpleModeRawButton = activity.findViewById(R.id.simpleModeRawButton);
        views.simpleFps30Button = activity.findViewById(R.id.simpleFps30Button);
        views.simpleFps45Button = activity.findViewById(R.id.simpleFps45Button);
        views.simpleFps60Button = activity.findViewById(R.id.simpleFps60Button);
        views.simpleFps90Button = activity.findViewById(R.id.simpleFps90Button);
        views.simpleFps120Button = activity.findViewById(R.id.simpleFps120Button);
        views.simpleFps144Button = activity.findViewById(R.id.simpleFps144Button);
        views.simpleApplyButton = activity.findViewById(R.id.simpleApplyButton);
        return views;
    }
}
