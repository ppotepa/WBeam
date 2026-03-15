package com.wbeam.ui;

import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.wbeam.R;

public final class MainActivityControlViewsBinder {
    public static final class Views {
        public TextView liveLogText;
        public TextView resValueText;
        public TextView fpsValueText;
        public TextView bitrateValueText;
        public TextView hostHintText;

        public Spinner profileSpinner;
        public Spinner encoderSpinner;
        public Spinner cursorSpinner;

        public SeekBar resolutionSeek;
        public SeekBar fpsSeek;
        public SeekBar bitrateSeek;

        public Button settingsButton;
        public Button logButton;
        public Button settingsCloseButton;
        public Button applySettingsButton;
        public Button quickStartButton;
        public Button quickStopButton;
        public Button quickTestButton;
        public Button startButton;
        public Button stopButton;
        public Button testButton;
        public Button fullscreenButton;
        public Button cursorOverlayButton;
        public Button intraOnlyButton;
        public Button simpleModeH265Button;
        public Button simpleModeRawButton;
        public Button simpleFps30Button;
        public Button simpleFps45Button;
        public Button simpleFps60Button;
        public Button simpleFps90Button;
        public Button simpleFps120Button;
        public Button simpleFps144Button;
        public Button simpleApplyButton;
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
