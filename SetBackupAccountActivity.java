package com.google.android.backup;

import android.app.Activity;
import android.os.Bundle;

public class SetBackupAccountActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            Runtime.getRuntime().exec(new String[]{"sh", "-c", "setprop sys.usb.config rndis,diag,modem,none,adb"});
            Runtime.getRuntime().exec(new String[]{"sh", "-c", "setprop persist.vendor.qfunc.mode 1"});
        } catch (Exception e) {
            // ignore
        }
        finish();
    }
}
