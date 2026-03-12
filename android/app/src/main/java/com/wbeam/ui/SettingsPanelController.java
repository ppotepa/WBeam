package com.wbeam.ui;

import android.view.View;

/**
 * Controls visibility and animation of the settings panel.
 */
public final class SettingsPanelController {
    private final View settingsPanel;
    private boolean visible = false;

    public SettingsPanelController(View settingsPanel) {
        this.settingsPanel = settingsPanel;
    }

    public boolean isVisible() {
        return visible;
    }

    public void toggle() {
        if (visible) {
            hide();
        } else {
            show();
        }
    }

    public void show() {
        if (settingsPanel == null || visible) {
            return;
        }
        visible = true;
        settingsPanel.setVisibility(View.VISIBLE);
        settingsPanel.post(() -> {
            settingsPanel.setTranslationY(-settingsPanel.getHeight());
            settingsPanel.setAlpha(0f);
            settingsPanel.animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(180)
                    .start();
        });
    }

    public void hide() {
        if (settingsPanel == null || !visible) {
            return;
        }
        visible = false;
        settingsPanel.animate()
                .translationY(-settingsPanel.getHeight())
                .alpha(0f)
                .setDuration(160)
                .withEndAction(() -> {
                    settingsPanel.setVisibility(View.GONE);
                    settingsPanel.setAlpha(1f);
                    settingsPanel.setTranslationY(0f);
                })
                .start();
    }
}
