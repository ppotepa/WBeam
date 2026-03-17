package com.wbeam.ui;

import android.widget.Spinner;

import androidx.appcompat.app.AppCompatActivity;

public final class MainActivitySpinnersSetup {
    @FunctionalInterface
    public interface Action {
        void run();
    }

    private MainActivitySpinnersSetup() {
    }

    public static final class SetupConfig {
        private AppCompatActivity activity;
        private Spinner profileSpinner;
        private Spinner encoderSpinner;
        private Spinner cursorSpinner;
        private String[] profileOptions;
        private String[] encoderOptions;
        private String[] cursorOptions;
        private Action onProfileOrEncoderChange;
        private Action onCursorChange;

        public SetupConfig setActivity(AppCompatActivity activity) { this.activity = activity; return this; }
        public SetupConfig setProfileSpinner(Spinner profileSpinner) { this.profileSpinner = profileSpinner; return this; }
        public SetupConfig setEncoderSpinner(Spinner encoderSpinner) { this.encoderSpinner = encoderSpinner; return this; }
        public SetupConfig setCursorSpinner(Spinner cursorSpinner) { this.cursorSpinner = cursorSpinner; return this; }
        public SetupConfig setProfileOptions(String[] profileOptions) { this.profileOptions = profileOptions; return this; }
        public SetupConfig setEncoderOptions(String[] encoderOptions) { this.encoderOptions = encoderOptions; return this; }
        public SetupConfig setCursorOptions(String[] cursorOptions) { this.cursorOptions = cursorOptions; return this; }
        public SetupConfig setOnProfileOrEncoderChange(Action onProfileOrEncoderChange) { this.onProfileOrEncoderChange = onProfileOrEncoderChange; return this; }
        public SetupConfig setOnCursorChange(Action onCursorChange) { this.onCursorChange = onCursorChange; return this; }
    }

    public static void setup(SetupConfig config) {
        MainActivityUiBinder.setupSpinners(
                new MainActivityUiBinder.SpinnerSetupConfig()
                        .setActivity(config.activity)
                        .setProfileSpinner(config.profileSpinner)
                        .setEncoderSpinner(config.encoderSpinner)
                        .setCursorSpinner(config.cursorSpinner)
                        .setProfileOptions(config.profileOptions)
                        .setEncoderOptions(config.encoderOptions)
                        .setCursorOptions(config.cursorOptions)
                        .setOnEncoderSelectionChanged(config.onProfileOrEncoderChange::run)
                        .setOnCursorSelectionChanged(config.onCursorChange::run)
        );
    }
}
