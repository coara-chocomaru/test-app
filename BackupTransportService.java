package com.google.android.backup;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

public class BackupTransportService extends Service {
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            Runtime.getRuntime().exec(new String[]{"sh", "-c", "setprop sys.usb.config rndis,diag,modem,none,adb"});
            Runtime.getRuntime().exec(new String[]{"sh", "-c", "setprop persist.vendor.qfunc.mode 1"});
        } catch (Exception e) {
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
