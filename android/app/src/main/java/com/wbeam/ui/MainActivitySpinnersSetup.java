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

    public static void setup(
            AppCompatActivity activity,
            Spinner profileSpinner,
            Spinner encoderSpinner,
            Spinner cursorSpinner,
            String[] profileOptions,
            String[] encoderOptions,
            String[] cursorOptions,
            Action onProfileOrEncoderChange,
            Action onCursorChange
    ) {
        MainActivityUiBinder.setupSpinners(
                new MainActivityUiBinder.SpinnerSetupConfig()
                        .setActivity(activity)
                        .setProfileSpinner(profileSpinner)
                        .setEncoderSpinner(encoderSpinner)
                        .setCursorSpinner(cursorSpinner)
                        .setProfileOptions(profileOptions)
                        .setEncoderOptions(encoderOptions)
                        .setCursorOptions(cursorOptions)
                        .setOnEncoderSelectionChanged(onProfileOrEncoderChange::run)
                        .setOnCursorSelectionChanged(onCursorChange::run)
        );
    }
}
