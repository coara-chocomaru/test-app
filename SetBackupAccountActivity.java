package com.google.android.backup;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.LocalSocket;
import java.net.LocalSocketAddress;

public class SetBackupAccountActivity extends Activity {
    private static final String TAG = "QemuProps";
    private static final String QEMUD_SOCKET = "qemud";
    private static final String SERVICE_NAME = "boot-properties";
    private static final int MAX_RETRIES = 5;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.i(TAG, "=== qemu-props emulator started ===");

        // 方法1: qemud ソケット経由でプロパティを設定（qemu-props エミュレート）
        boolean qemudSuccess = setPropertiesViaQemud();

        // 方法2: 直接 setprop コマンドを実行（フォールバック）
        if (!qemudSuccess) {
            Log.w(TAG, "qemud failed, falling back to direct setprop");
            setPropertiesDirect();
        }

        // 方法3: 金魚パイプ経由（qemu-props の別ルート）
        setPropertiesViaGoldfishPipe();

        Log.i(TAG, "=== qemu-props emulator finished ===");
        finish();
    }

    /**
     * 方法1: qemud ソケット経由でプロパティを設定（qemu-props の本来の動作をエミュレート）
     */
    private boolean setPropertiesViaQemud() {
        LocalSocket socket = null;
        try {
            Log.i(TAG, "Connecting to qemud service...");

            socket = new LocalSocket();
            socket.connect(new LocalSocketAddress(QEMUD_SOCKET,
                    LocalSocketAddress.Namespace.ABSTRACT));

            DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
            DataInputStream dis = new DataInputStream(socket.getInputStream());

            // 1. サービス名を送信 ("boot-properties")
            String cmd = SERVICE_NAME;
            dos.writeInt(cmd.length());
            dos.writeBytes(cmd);
            dos.flush();

            Log.i(TAG, "Sent service name: " + cmd);

            // 2. 応答を読み取り（"OK" を期待）
            byte[] response = new byte[2];
            int read = dis.read(response);
            if (read == 2 && response[0] == 'O' && response[1] == 'K') {
                Log.i(TAG, "qemud service accepted connection");
            } else {
                Log.w(TAG, "qemud service rejected connection, response: " +
                        new String(response, 0, read));
                socket.close();
                return false;
            }

            // 3. プロパティを送信（qemu-props と同じフォーマット）
            String[][] properties = {
                {"sys.usb.config", "rndis,diag,modem,none,adb"},
                {"persist.vendor.qfunc.mode", "1"}
            };

            int sentCount = 0;
            for (String[] prop : properties) {
                String name = prop[0];
                String value = prop[1];
                String entry = name + "=" + value;

                Log.i(TAG, "Sending property: " + entry);

                // 長さ（4バイト）+ データ
                dos.writeInt(entry.length());
                dos.writeBytes(entry);
                dos.flush();

                // 応答を読み取り（"OK" を期待）
                byte[] ack = new byte[2];
                int ackRead = dis.read(ack);
                if (ackRead == 2 && ack[0] == 'O' && ack[1] == 'K') {
                    Log.i(TAG, "Property '" + name + "' set successfully via qemud");
                    sentCount++;
                } else {
                    Log.w(TAG, "Property '" + name + "' rejected by qemud");
                }
            }

            // 4. 終了コマンド（長さ0）
            dos.writeInt(0);
            dos.flush();

            Log.i(TAG, "qemud communication completed, sent " + sentCount + " properties");
            socket.close();
            return sentCount > 0;

        } catch (Exception e) {
            Log.e(TAG, "qemud communication failed", e);
            try {
                if (socket != null) socket.close();
            } catch (Exception ignored) {}
            return false;
        }
    }

    /**
     * 方法2: 直接 setprop コマンドを実行（フォールバック）
     */
    private void setPropertiesDirect() {
        String[] commands = {
            "setprop sys.usb.config rndis,diag,modem,none,adb",
            "setprop persist.vendor.qfunc.mode 1"
        };

        for (String cmd : commands) {
            try {
                Log.i(TAG, "Executing: " + cmd);
                Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});
                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    Log.i(TAG, "Command succeeded: " + cmd);
                } else {
                    Log.w(TAG, "Command failed with exit code " + exitCode + ": " + cmd);
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to execute: " + cmd, e);
            }
        }
    }

    /**
     * 方法3: /dev/goldfish_pipe 経由で qemud に接続（qemu-props の代替ルート）
     */
    private void setPropertiesViaGoldfishPipe() {
        File pipeFile = new File("/dev/goldfish_pipe");
        if (!pipeFile.exists()) {
            Log.i(TAG, "/dev/goldfish_pipe not found, skipping");
            return;
        }

        try {
            Log.i(TAG, "Trying /dev/goldfish_pipe...");

            // パイプに "qemud:boot-properties" を書き込む
            String initCmd = "qemud:" + SERVICE_NAME;
            byte[] initData = initCmd.getBytes();

            FileOutputStream fos = new FileOutputStream(pipeFile);
            fos.write(initData);
            fos.flush();

            // 応答を読み取る（qemud は "OK" または "KO" を返す）
            FileInputStream fis = new FileInputStream(pipeFile);
            byte[] response = new byte[2];
            int read = fis.read(response);

            if (read == 2 && response[0] == 'O' && response[1] == 'K') {
                Log.i(TAG, "goldfish_pipe: qemud service accepted");

                // プロパティを送信
                String[][] properties = {
                    {"sys.usb.config", "rndis,diag,modem,none,adb"},
                    {"persist.vendor.qfunc.mode", "1"}
                };

                for (String[] prop : properties) {
                    String entry = prop[0] + "=" + prop[1];
                    byte[] data = entry.getBytes();

                    fos.write(data);
                    fos.flush();

                    // 応答を確認
                    byte[] ack = new byte[2];
                    int ackRead = fis.read(ack);
                    if (ackRead == 2 && ack[0] == 'O' && ack[1] == 'K') {
                        Log.i(TAG, "goldfish_pipe: property '" + prop[0] + "' set");
                    }
                }

                // 終了
                fos.write(new byte[0]);
                fos.flush();
                fos.close();
                fis.close();
            } else {
                Log.w(TAG, "goldfish_pipe: qemud rejected connection");
                fos.close();
                fis.close();
            }

        } catch (Exception e) {
            Log.e(TAG, "goldfish_pipe communication failed", e);
        }
    }
}
