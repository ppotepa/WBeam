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
                activity,
                profileSpinner,
                encoderSpinner,
                cursorSpinner,
                profileOptions,
                encoderOptions,
                cursorOptions,
                onProfileOrEncoderChange::run,
                onCursorChange::run
        );
    }
}
