package com.wbeam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class UsbAttachReceiver extends BroadcastReceiver {
    private static final String TAG = "WBeamUsbAttach";
    private static final String ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }

        String action = intent.getAction();
        boolean shouldLaunch = false;

        if (Intent.ACTION_POWER_CONNECTED.equals(action)) {
            shouldLaunch = true;
        } else if (ACTION_USB_STATE.equals(action)) {
            shouldLaunch = intent.getBooleanExtra("connected", false);
        }

        if (!shouldLaunch) {
            return;
        }

        Log.i(TAG, "USB attach event received, launching MainActivity");

        Intent launch = new Intent(context, MainActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(launch);
    }
}
