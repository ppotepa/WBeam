package com.wbeam.ui;

import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.wbeam.R;

public final class MainActivityControlViewsBinder {
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

        public TextView getLiveLogText() {
            return liveLogText;
        }

        public void setLiveLogText(TextView liveLogText) {
            this.liveLogText = liveLogText;
        }

        public TextView getResValueText() {
            return resValueText;
        }

        public void setResValueText(TextView resValueText) {
            this.resValueText = resValueText;
        }

        public TextView getFpsValueText() {
            return fpsValueText;
        }

        public void setFpsValueText(TextView fpsValueText) {
            this.fpsValueText = fpsValueText;
        }

        public TextView getBitrateValueText() {
            return bitrateValueText;
        }

        public void setBitrateValueText(TextView bitrateValueText) {
            this.bitrateValueText = bitrateValueText;
        }

        public TextView getHostHintText() {
            return hostHintText;
        }

        public void setHostHintText(TextView hostHintText) {
            this.hostHintText = hostHintText;
        }

        public Spinner getProfileSpinner() {
            return profileSpinner;
        }

        public void setProfileSpinner(Spinner profileSpinner) {
            this.profileSpinner = profileSpinner;
        }

        public Spinner getEncoderSpinner() {
            return encoderSpinner;
        }

        public void setEncoderSpinner(Spinner encoderSpinner) {
            this.encoderSpinner = encoderSpinner;
        }

        public Spinner getCursorSpinner() {
            return cursorSpinner;
        }

        public void setCursorSpinner(Spinner cursorSpinner) {
            this.cursorSpinner = cursorSpinner;
        }

        public SeekBar getResolutionSeek() {
            return resolutionSeek;
        }

        public void setResolutionSeek(SeekBar resolutionSeek) {
            this.resolutionSeek = resolutionSeek;
        }

        public SeekBar getFpsSeek() {
            return fpsSeek;
        }

        public void setFpsSeek(SeekBar fpsSeek) {
            this.fpsSeek = fpsSeek;
        }

        public SeekBar getBitrateSeek() {
            return bitrateSeek;
        }

        public void setBitrateSeek(SeekBar bitrateSeek) {
            this.bitrateSeek = bitrateSeek;
        }

        public Button getSettingsButton() {
            return settingsButton;
        }

        public void setSettingsButton(Button settingsButton) {
            this.settingsButton = settingsButton;
        }

        public Button getLogButton() {
            return logButton;
        }

        public void setLogButton(Button logButton) {
            this.logButton = logButton;
        }

        public Button getSettingsCloseButton() {
            return settingsCloseButton;
        }

        public void setSettingsCloseButton(Button settingsCloseButton) {
            this.settingsCloseButton = settingsCloseButton;
        }

        public Button getApplySettingsButton() {
            return applySettingsButton;
        }

        public void setApplySettingsButton(Button applySettingsButton) {
            this.applySettingsButton = applySettingsButton;
        }

        public Button getQuickStartButton() {
            return quickStartButton;
        }

        public void setQuickStartButton(Button quickStartButton) {
            this.quickStartButton = quickStartButton;
        }

        public Button getQuickStopButton() {
            return quickStopButton;
        }

        public void setQuickStopButton(Button quickStopButton) {
            this.quickStopButton = quickStopButton;
        }

        public Button getQuickTestButton() {
            return quickTestButton;
        }

        public void setQuickTestButton(Button quickTestButton) {
            this.quickTestButton = quickTestButton;
        }

        public Button getStartButton() {
            return startButton;
        }

        public void setStartButton(Button startButton) {
            this.startButton = startButton;
        }

        public Button getStopButton() {
            return stopButton;
        }

        public void setStopButton(Button stopButton) {
            this.stopButton = stopButton;
        }

        public Button getTestButton() {
            return testButton;
        }

        public void setTestButton(Button testButton) {
            this.testButton = testButton;
        }

        public Button getFullscreenButton() {
            return fullscreenButton;
        }

        public void setFullscreenButton(Button fullscreenButton) {
            this.fullscreenButton = fullscreenButton;
        }

        public Button getCursorOverlayButton() {
            return cursorOverlayButton;
        }

        public void setCursorOverlayButton(Button cursorOverlayButton) {
            this.cursorOverlayButton = cursorOverlayButton;
        }

        public Button getIntraOnlyButton() {
            return intraOnlyButton;
        }

        public void setIntraOnlyButton(Button intraOnlyButton) {
            this.intraOnlyButton = intraOnlyButton;
        }

        public Button getSimpleModeH265Button() {
            return simpleModeH265Button;
        }

        public void setSimpleModeH265Button(Button simpleModeH265Button) {
            this.simpleModeH265Button = simpleModeH265Button;
        }

        public Button getSimpleModeRawButton() {
            return simpleModeRawButton;
        }

        public void setSimpleModeRawButton(Button simpleModeRawButton) {
            this.simpleModeRawButton = simpleModeRawButton;
        }

        public Button getSimpleFps30Button() {
            return simpleFps30Button;
        }

        public void setSimpleFps30Button(Button simpleFps30Button) {
            this.simpleFps30Button = simpleFps30Button;
        }

        public Button getSimpleFps45Button() {
            return simpleFps45Button;
        }

        public void setSimpleFps45Button(Button simpleFps45Button) {
            this.simpleFps45Button = simpleFps45Button;
        }

        public Button getSimpleFps60Button() {
            return simpleFps60Button;
        }

        public void setSimpleFps60Button(Button simpleFps60Button) {
            this.simpleFps60Button = simpleFps60Button;
        }

        public Button getSimpleFps90Button() {
            return simpleFps90Button;
        }

        public void setSimpleFps90Button(Button simpleFps90Button) {
            this.simpleFps90Button = simpleFps90Button;
        }

        public Button getSimpleFps120Button() {
            return simpleFps120Button;
        }

        public void setSimpleFps120Button(Button simpleFps120Button) {
            this.simpleFps120Button = simpleFps120Button;
        }

        public Button getSimpleFps144Button() {
            return simpleFps144Button;
        }

        public void setSimpleFps144Button(Button simpleFps144Button) {
            this.simpleFps144Button = simpleFps144Button;
        }

        public Button getSimpleApplyButton() {
            return simpleApplyButton;
        }

        public void setSimpleApplyButton(Button simpleApplyButton) {
            this.simpleApplyButton = simpleApplyButton;
        }
    }

    private MainActivityControlViewsBinder() {
    }

    public static Views bind(AppCompatActivity activity) {
        Views views = new Views();
        views.setLiveLogText(activity.findViewById(R.id.liveLogText));
        views.setResValueText(activity.findViewById(R.id.resValueText));
        views.setFpsValueText(activity.findViewById(R.id.fpsValueText));
        views.setBitrateValueText(activity.findViewById(R.id.bitrateValueText));
        views.setHostHintText(activity.findViewById(R.id.hostHintText));

        views.setProfileSpinner(activity.findViewById(R.id.profileSpinner));
        views.setEncoderSpinner(activity.findViewById(R.id.encoderSpinner));
        views.setCursorSpinner(activity.findViewById(R.id.cursorSpinner));

        views.setResolutionSeek(activity.findViewById(R.id.resolutionSeek));
        views.setFpsSeek(activity.findViewById(R.id.fpsSeek));
        views.setBitrateSeek(activity.findViewById(R.id.bitrateSeek));

        views.setSettingsButton(activity.findViewById(R.id.settingsButton));
        views.setLogButton(activity.findViewById(R.id.logButton));
        views.setSettingsCloseButton(activity.findViewById(R.id.settingsCloseButton));
        views.setApplySettingsButton(activity.findViewById(R.id.applySettingsButton));
        views.setQuickStartButton(activity.findViewById(R.id.quickStartButton));
        views.setQuickStopButton(activity.findViewById(R.id.quickStopButton));
        views.setQuickTestButton(activity.findViewById(R.id.quickTestButton));
        views.setStartButton(activity.findViewById(R.id.startButton));
        views.setStopButton(activity.findViewById(R.id.stopButton));
        views.setTestButton(activity.findViewById(R.id.testButton));
        views.setFullscreenButton(activity.findViewById(R.id.fullscreenButton));
        views.setCursorOverlayButton(activity.findViewById(R.id.cursorOverlayButton));
        views.setIntraOnlyButton(activity.findViewById(R.id.intraOnlyButton));
        views.setSimpleModeH265Button(activity.findViewById(R.id.simpleModeH265Button));
        views.setSimpleModeRawButton(activity.findViewById(R.id.simpleModeRawButton));
        views.setSimpleFps30Button(activity.findViewById(R.id.simpleFps30Button));
        views.setSimpleFps45Button(activity.findViewById(R.id.simpleFps45Button));
        views.setSimpleFps60Button(activity.findViewById(R.id.simpleFps60Button));
        views.setSimpleFps90Button(activity.findViewById(R.id.simpleFps90Button));
        views.setSimpleFps120Button(activity.findViewById(R.id.simpleFps120Button));
        views.setSimpleFps144Button(activity.findViewById(R.id.simpleFps144Button));
        views.setSimpleApplyButton(activity.findViewById(R.id.simpleApplyButton));
        return views;
    }
}
