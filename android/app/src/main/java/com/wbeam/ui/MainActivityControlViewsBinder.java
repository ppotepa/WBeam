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
        private final TextView liveLogText;
        private final TextView resValueText;
        private final TextView fpsValueText;
        private final TextView bitrateValueText;
        private final TextView hostHintText;
        private final Spinner profileSpinner;
        private final Spinner encoderSpinner;
        private final Spinner cursorSpinner;
        private final SeekBar resolutionSeek;
        private final SeekBar fpsSeek;
        private final SeekBar bitrateSeek;
        private final Button settingsButton;
        private final Button logButton;
        private final Button settingsCloseButton;
        private final Button applySettingsButton;
        private final Button quickStartButton;
        private final Button quickStopButton;
        private final Button quickTestButton;
        private final Button startButton;
        private final Button stopButton;
        private final Button testButton;
        private final Button fullscreenButton;
        private final Button cursorOverlayButton;
        private final Button intraOnlyButton;
        private final Button simpleModeH265Button;
        private final Button simpleModeRawButton;
        private final Button simpleFps30Button;
        private final Button simpleFps45Button;
        private final Button simpleFps60Button;
        private final Button simpleFps90Button;
        private final Button simpleFps120Button;
        private final Button simpleFps144Button;
        private final Button simpleApplyButton;

        private Views(
                TextView liveLogText,
                TextView resValueText,
                TextView fpsValueText,
                TextView bitrateValueText,
                TextView hostHintText,
                Spinner profileSpinner,
                Spinner encoderSpinner,
                Spinner cursorSpinner,
                SeekBar resolutionSeek,
                SeekBar fpsSeek,
                SeekBar bitrateSeek,
                Button settingsButton,
                Button logButton,
                Button settingsCloseButton,
                Button applySettingsButton,
                Button quickStartButton,
                Button quickStopButton,
                Button quickTestButton,
                Button startButton,
                Button stopButton,
                Button testButton,
                Button fullscreenButton,
                Button cursorOverlayButton,
                Button intraOnlyButton,
                Button simpleModeH265Button,
                Button simpleModeRawButton,
                Button simpleFps30Button,
                Button simpleFps45Button,
                Button simpleFps60Button,
                Button simpleFps90Button,
                Button simpleFps120Button,
                Button simpleFps144Button,
                Button simpleApplyButton
        ) {
            this.liveLogText = liveLogText;
            this.resValueText = resValueText;
            this.fpsValueText = fpsValueText;
            this.bitrateValueText = bitrateValueText;
            this.hostHintText = hostHintText;
            this.profileSpinner = profileSpinner;
            this.encoderSpinner = encoderSpinner;
            this.cursorSpinner = cursorSpinner;
            this.resolutionSeek = resolutionSeek;
            this.fpsSeek = fpsSeek;
            this.bitrateSeek = bitrateSeek;
            this.settingsButton = settingsButton;
            this.logButton = logButton;
            this.settingsCloseButton = settingsCloseButton;
            this.applySettingsButton = applySettingsButton;
            this.quickStartButton = quickStartButton;
            this.quickStopButton = quickStopButton;
            this.quickTestButton = quickTestButton;
            this.startButton = startButton;
            this.stopButton = stopButton;
            this.testButton = testButton;
            this.fullscreenButton = fullscreenButton;
            this.cursorOverlayButton = cursorOverlayButton;
            this.intraOnlyButton = intraOnlyButton;
            this.simpleModeH265Button = simpleModeH265Button;
            this.simpleModeRawButton = simpleModeRawButton;
            this.simpleFps30Button = simpleFps30Button;
            this.simpleFps45Button = simpleFps45Button;
            this.simpleFps60Button = simpleFps60Button;
            this.simpleFps90Button = simpleFps90Button;
            this.simpleFps120Button = simpleFps120Button;
            this.simpleFps144Button = simpleFps144Button;
            this.simpleApplyButton = simpleApplyButton;
        }

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
        return new Views(
                activity.findViewById(R.id.liveLogText),
                activity.findViewById(R.id.resValueText),
                activity.findViewById(R.id.fpsValueText),
                activity.findViewById(R.id.bitrateValueText),
                activity.findViewById(R.id.hostHintText),
                activity.findViewById(R.id.profileSpinner),
                activity.findViewById(R.id.encoderSpinner),
                activity.findViewById(R.id.cursorSpinner),
                activity.findViewById(R.id.resolutionSeek),
                activity.findViewById(R.id.fpsSeek),
                activity.findViewById(R.id.bitrateSeek),
                activity.findViewById(R.id.settingsButton),
                activity.findViewById(R.id.logButton),
                activity.findViewById(R.id.settingsCloseButton),
                activity.findViewById(R.id.applySettingsButton),
                activity.findViewById(R.id.quickStartButton),
                activity.findViewById(R.id.quickStopButton),
                activity.findViewById(R.id.quickTestButton),
                activity.findViewById(R.id.startButton),
                activity.findViewById(R.id.stopButton),
                activity.findViewById(R.id.testButton),
                activity.findViewById(R.id.fullscreenButton),
                activity.findViewById(R.id.cursorOverlayButton),
                activity.findViewById(R.id.intraOnlyButton),
                activity.findViewById(R.id.simpleModeH265Button),
                activity.findViewById(R.id.simpleModeRawButton),
                activity.findViewById(R.id.simpleFps30Button),
                activity.findViewById(R.id.simpleFps45Button),
                activity.findViewById(R.id.simpleFps60Button),
                activity.findViewById(R.id.simpleFps90Button),
                activity.findViewById(R.id.simpleFps120Button),
                activity.findViewById(R.id.simpleFps144Button),
                activity.findViewById(R.id.simpleApplyButton)
        );
    }
}
