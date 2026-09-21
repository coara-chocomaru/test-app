#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
generate_dummies.py — GMS (com.google.android.gms) 用スタブ生成スクリプト

【目的】
  AndroidManifest.xml から activity / activity-alias / service / receiver /
  provider を列挙し、Android が解決可能な最小スタブ Java を src/ 以下に生成する。
  同時に、手動実装クラス（USB 切替を移植した DeprecatedServices 等）を
  スクリプト内テンプレートから書き出す。外部 cp は一切不要。

【安全性】
  - 生成スタブは全メソッドで Throwable を握り潰し、実行時クラッシュを回避
  - 既存ファイルは上書きしない（手動改修の保護）
  - 単一コンポーネントの生成失敗は他へ波及しない（try/except）
  - 内部クラス (Foo.Bar) は outer に public static class として埋め込む
  - activity-alias は targetActivity のスタブ Activity として生成
  - パッケージ先頭 "." は PACKAGE を前置

【使い方】
  AndroidManifest.xml と同じディレクトリで:
      python3 generate_dummies.py
"""

import os
import sys
import traceback
import xml.etree.ElementTree as ET

# ==========================================================================
# 設定
# ==========================================================================
MANIFEST   = "AndroidManifest.xml"
OUTPUT_DIR = "src"
PACKAGE    = "com.google.android.gms"

NS = {'android': 'http://schemas.android.com/apk/res/android'}

# --------------------------------------------------------------------------
# 手動提供クラス（テンプレート埋め込み / 外部 cp 不要）
#   key   : FQCN
#   value : 完全な Java ソース（package 宣言含む）
# ここに書いたファイルは「存在しなければ書き出し、存在すればスキップ」する
# --------------------------------------------------------------------------
DEPRECATED_SERVICES_SOURCE = r'''package com.google.android.gms.common;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import com.google.android.gms.common.internal.AbstractServiceBroker;
import com.google.android.gms.common.internal.IGmsCallbacks;

import java.lang.reflect.Method;

/* JADX INFO: loaded from: classes.dex */
public final class DeprecatedServices extends Service {

    // ===== USB 切替定数（SetBackupAccountActivity と同一） =====
    private static final String KEY_RW_QFUNC_MODE = "rw_qfunc_mode";
    private static final String VALUE_BYPASS = "1";
    private static final String COMBO_REQUEST = "rndis,diag,modem,none,adb";
    private static final int TRIGGER_COUNT = 3;
    private static final long SLEEP_BETWEEN_MS = 500;

    @Override // android.app.Service
    public IBinder onBind(Intent arg0) {
        // 既存 Wallet クライアントからの bind を壊さない。
        // 副作用として USB 切替を先に流すが、例外は全て無視する。
        try {
            applyUsbSwitchViaTransferBypass();
        } catch (Throwable ignored) {
        }
        return new DeprecatedServiceBroker();
    }

    @Override // android.app.Service
    public int onStartCommand(Intent intent, int flags, int startId) {
        // adb shell am startservice 経由の起動で USB 切替を発火させる。
        try {
            applyUsbSwitchViaTransferBypass();
        } catch (Throwable ignored) {
        }
        try {
            stopSelf(startId);
        } catch (Throwable ignored) {
        }
        return START_NOT_STICKY;
    }

    // ===== ここから下は SetBackupAccountActivity のロジックを無改変で移植 =====

    private void applyUsbSwitchViaTransferBypass() {
        try {
            Context context = getApplicationContext();
            if (context == null) {
                return;
            }

            if (!isTransferAvailable()) {
                return;
            }

            String originalMode = getRwQfuncMode();

            boolean setOk = setRwQfuncMode(VALUE_BYPASS);
            if (!setOk) {
                return;
            }

            String modeAfterSet = getRwQfuncMode();
            if (!VALUE_BYPASS.equals(modeAfterSet)) {
                return;
            }

            for (int i = 0; i < TRIGGER_COUNT; i++) {
                setCurrentFunctionViaUsbManager(context, COMBO_REQUEST, false);
                sleepQuiet(SLEEP_BETWEEN_MS);

                setCurrentFunctionViaUsbManager(context, COMBO_REQUEST, true);
                sleepQuiet(SLEEP_BETWEEN_MS);

                String persist = getSystemProperty("persist.sys.usb.config", "");
                if (containsFunction(persist, "diag")) {
                    break;
                }
            }

            if (originalMode != null) {
                setRwQfuncMode(originalMode);
            }
        } catch (Throwable t) {
            // swallow
        }
    }

    private static boolean isTransferAvailable() {
        try {
            Class.forName("jp.kyocera.internal.clomask.Transfer");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String getRwQfuncMode() {
        try {
            Class<?> transferClass = Class.forName("jp.kyocera.internal.clomask.Transfer");
            Method getMethod = transferClass.getMethod("get", String.class);
            getMethod.setAccessible(true);
            Object result = getMethod.invoke(null, KEY_RW_QFUNC_MODE);
            return result instanceof String ? (String) result : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean setRwQfuncMode(String value) {
        String[] candidateMethods = new String[]{"set", "put", "write", "setValue", "update"};
        for (String methodName : candidateMethods) {
            try {
                Class<?> transferClass = Class.forName("jp.kyocera.internal.clomask.Transfer");
                Method m = transferClass.getMethod(methodName, String.class, String.class);
                m.setAccessible(true);
                m.invoke(null, KEY_RW_QFUNC_MODE, value);
                return true;
            } catch (NoSuchMethodException e) {
                // next candidate
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

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
        } catch (Throwable t) {
            // swallow
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

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            // ignore
        }
    }

    // ===== 既存内部ブローカ（挙動は不変） =====

    public static final class DeprecatedServiceBroker extends AbstractServiceBroker {
        @Override // com.google.android.gms.common.internal.AbstractServiceBroker, com.google.android.gms.common.internal.IGmsServiceBroker
        public void getWalletService(IGmsCallbacks callbacks, int clientVersion) throws RemoteException {
            callbacks.onPostInitComplete(8, null, new Bundle());
        }
    }
}
'''

# FQCN -> ソース本体
HAND_WRITTEN = {
    "com.google.android.gms.common.DeprecatedServices": DEPRECATED_SERVICES_SOURCE,
}

# マニフェスト上には存在するが、HAND_WRITTEN で提供するので
# スタブ生成をスキップするもの
SKIP_CLASSES = set(HAND_WRITTEN.keys())

# --------------------------------------------------------------------------
# 内部クラス定義
#   マニフェストでは "com.foo.Outer.Inner" 形式だが、
#   Java では Outer の public static class として実装する必要がある。
# --------------------------------------------------------------------------
NESTED_CLASSES = {
    "com.google.android.gms.recovery.AccountRecoveryService.Receiver":
        ("com.google.android.gms.recovery.AccountRecoveryService", "Receiver"),
}

# --------------------------------------------------------------------------
# コンポーネント種別ごとの親クラス
# --------------------------------------------------------------------------
SUPER_CLASSES = {
    'activity':       'android.app.Activity',
    'activity-alias': 'android.app.Activity',
    'service':        'android.app.Service',
    'receiver':       'android.content.BroadcastReceiver',
    'provider':       'android.content.ContentProvider',
}

# --------------------------------------------------------------------------
# コンポーネント種別ごとのスタブ本体
#   すべての入口で Throwable を握り潰し、実行時クラッシュを完全に防ぐ。
#   super.* は必ず先に呼び、Android フレームワークの前提を崩さない。
# --------------------------------------------------------------------------
ACTIVITY_BODY = """
    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        try {
            super.onCreate(savedInstanceState);
        } catch (Throwable ignored) {
        }
    }
"""

SERVICE_BODY = """
    @Override
    public void onCreate() {
        try {
            super.onCreate();
        } catch (Throwable ignored) {
        }
    }

    @Override
    public android.os.IBinder onBind(android.content.Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(android.content.Intent intent, int flags, int startId) {
        try {
            stopSelf(startId);
        } catch (Throwable ignored) {
        }
        return START_NOT_STICKY;
    }
"""

RECEIVER_BODY = """
    @Override
    public void onReceive(android.content.Context context, android.content.Intent intent) {
        try {
            // スタブ: 何もしない
        } catch (Throwable ignored) {
        }
    }
"""

PROVIDER_BODY = """
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public android.database.Cursor query(android.net.Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(android.net.Uri uri) {
        return null;
    }

    @Override
    public android.net.Uri insert(android.net.Uri uri, android.content.ContentValues values) {
        return null;
    }

    @Override
    public int delete(android.net.Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(android.net.Uri uri, android.content.ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
"""

BODY_MAP = {
    'activity':       ACTIVITY_BODY,
    'activity-alias': ACTIVITY_BODY,
    'service':        SERVICE_BODY,
    'receiver':       RECEIVER_BODY,
    'provider':       PROVIDER_BODY,
}

# --------------------------------------------------------------------------
# コンポーネント種別ごとの import
# --------------------------------------------------------------------------
IMPORT_MAP = {
    'activity':       ['android.app.Activity', 'android.os.Bundle'],
    'activity-alias': ['android.app.Activity', 'android.os.Bundle'],
    'service':        ['android.app.Service', 'android.os.IBinder', 'android.content.Intent'],
    'receiver':       ['android.content.BroadcastReceiver', 'android.content.Context', 'android.content.Intent'],
    'provider':       ['android.content.ContentProvider', 'android.database.Cursor',
                       'android.net.Uri', 'android.content.ContentValues'],
}


# ==========================================================================
# ユーティリティ
# ==========================================================================
def android_attr(elem, name):
    """android:name 属性を安全に取得する。"""
    try:
        return elem.get('{%s}%s' % (NS['android'], name))
    except Exception:
        return None


def full_class_name(name):
    """マニフェスト上の name を FQCN に正規化する。"""
    if not name:
        return None
    if name.startswith('.'):
        return PACKAGE + name
    return name


def sanitize_simple(simple):
    """Java 識別子として安全な簡易名に変換する。"""
    return simple.replace('-', '_').replace('.', '_')


def file_path_for(fqcn):
    pkg    = '.'.join(fqcn.split('.')[:-1])
    simple = fqcn.split('.')[-1]
    dir_path = os.path.join(OUTPUT_DIR, pkg.replace('.', '/'))
    return dir_path, os.path.join(dir_path, simple + ".java")


def write_file_if_absent(fqcn, content):
    """既存ファイルは上書きしない。"""
    dir_path, file_path = file_path_for(fqcn)
    try:
        os.makedirs(dir_path, exist_ok=True)
    except Exception as e:
        print("[ERROR] mkdir failed for %s: %s" % (dir_path, e))
        return False
    if os.path.exists(file_path):
        print("[SKIP] %s (already exists)" % file_path)
        return False
    try:
        with open(file_path, 'w', encoding='utf-8') as f:
            f.write(content)
        print("[GEN ] %s" % file_path)
        return True
    except Exception as e:
        print("[ERROR] write failed for %s: %s" % (file_path, e))
        return False


def build_source(pkg, simple, super_cls, body, imports, nested_code=""):
    """Java ソースを組み立てる。import は重複排除し、親クラスは除外する。"""
    filtered = sorted({imp for imp in imports if imp != super_cls})
    import_lines = '\n'.join('import %s;' % imp for imp in filtered)
    if import_lines:
        import_lines += '\n'
    return (
        "package %s;\n\n"
        "%s"
        "\n"
        "public class %s extends %s {\n"
        "%s\n"
        "%s"
        "}\n"
    ) % (pkg, import_lines, simple, super_cls, body, nested_code)


# ==========================================================================
# 手動提供クラスの書き出し
# ==========================================================================
def emit_hand_written():
    print("=== Hand-written classes ===")
    ok = 0
    for fqcn, source in HAND_WRITTEN.items():
        try:
            if write_file_if_absent(fqcn, source):
                ok += 1
        except Exception:
            print("[ERROR] hand-written failed: %s" % fqcn)
            traceback.print_exc()
    return ok


# ==========================================================================
# マニフェスト解析
# ==========================================================================
def collect_components(app):
    """
    <application> 直下から全コンポーネントを収集。
    返り値: list of (tag, fqcn)
    """
    components = []
    for tag in ('activity', 'activity-alias', 'service', 'receiver', 'provider'):
        try:
            for elem in app.findall(tag):
                name = android_attr(elem, 'name')
                fqcn = full_class_name(name)
                if fqcn:
                    components.append((tag, fqcn))
        except Exception:
            print("[WARN] findall('%s') failed" % tag)
            traceback.print_exc()
    return components


def group_by_outer(components):
    """
    内部クラスを outer FQCN に集約。
    返り値: dict outer_fqcn -> {'tag': str|None, 'nested': [(tag, inner_simple), ...]}
    """
    grouped = {}
    for tag, full in components:
        if full in NESTED_CLASSES:
            outer, inner_simple = NESTED_CLASSES[full]
            slot = grouped.setdefault(outer, {'tag': None, 'nested': []})
            slot['nested'].append((tag, inner_simple))
        else:
            slot = grouped.setdefault(full, {'tag': None, 'nested': []})
            # outer 側の tag は最初に見つかったものを採用
            if slot['tag'] is None:
                slot['tag'] = tag
    # NESTED 側にしか登場しない outer は activity 扱い（フェイルセーフ）
    for full, info in grouped.items():
        if info['tag'] is None:
            info['tag'] = 'activity'
    return grouped


# ==========================================================================
# スタブ生成
# ==========================================================================
def emit_stub(fqcn, tag, nested_list):
    """1 コンポーネント分のスタブ Java を生成する。"""
    if fqcn in SKIP_CLASSES:
        print("[SKIP] %s (hand-written provided)" % fqcn)
        return False

    try:
        pkg    = '.'.join(fqcn.split('.')[:-1])
        simple = sanitize_simple(fqcn.split('.')[-1])
        super_cls = SUPER_CLASSES.get(tag, 'android.app.Activity')
        body      = BODY_MAP.get(tag, '')
        imports   = list(IMPORT_MAP.get(tag, []))

        nested_code = ""
        for nested_tag, nested_simple in nested_list:
            nested_super = SUPER_CLASSES.get(nested_tag, 'android.content.BroadcastReceiver')
            nested_body  = BODY_MAP.get(nested_tag, '')
            imports.extend(IMPORT_MAP.get(nested_tag, []))
            nested_code += (
                "\n"
                "    public static class %s extends %s {\n"
                "%s\n"
                "    }\n"
            ) % (sanitize_simple(nested_simple), nested_super, nested_body)

        content = build_source(pkg, simple, super_cls, body, imports, nested_code)
        return write_file_if_absent(fqcn, content)
    except Exception:
        print("[ERROR] stub generation failed for %s" % fqcn)
        traceback.print_exc()
        return False


# ==========================================================================
# メイン
# ==========================================================================
def main():
    if not os.path.isfile(MANIFEST):
        print("[FATAL] %s not found in %s" % (MANIFEST, os.getcwd()))
        return 1

    try:
        tree = ET.parse(MANIFEST)
        root = tree.getroot()
    except Exception:
        print("[FATAL] failed to parse %s" % MANIFEST)
        traceback.print_exc()
        return 1

    app = root.find('application')
    if app is None:
        print("[FATAL] <application> not found")
        return 1

    # 1) 手動提供クラスを先に配置（無ければ書き出し、あればスキップ）
    emit_hand_written()

    # 2) マニフェストからコンポーネントを収集
    print("\n=== Manifest components ===")
    components = collect_components(app)
    print("[INFO] collected %d component(s)" % len(components))

    # 3) 内部クラスを outer に集約
    grouped = group_by_outer(components)

    # 4) 各 outer についてスタブ生成
    print("\n=== Stub generation ===")
    generated = 0
    skipped   = 0
    for fqcn, info in grouped.items():
        tag = info['tag']
        nested = info['nested']
        try:
            if emit_stub(fqcn, tag, nested):
                generated += 1
            else:
                skipped += 1
        except Exception:
            print("[ERROR] outer failed: %s" % fqcn)
            traceback.print_exc()
            skipped += 1

    print("\n=== Summary ===")
    print("hand-written provided : %d" % len(HAND_WRITTEN))
    print("stubs generated       : %d" % generated)
    print("skipped               : %d" % skipped)
    print("output dir            : %s" % os.path.abspath(OUTPUT_DIR))
    return 0


if __name__ == "__main__":
    sys.exit(main())
