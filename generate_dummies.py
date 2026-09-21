#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
generate_dummies.py — GMS (com.google.android.gms) 用ダミー生成

【元の .py からの継承】
  - AndroidManifest.xml を ElementTree でパース
  - <application> 直下の activity / service / receiver / provider を列挙
  - 先頭 "." は PACKAGE を前置して FQCN に正規化
  - 種別ごとの最小スタブ Java を src/<pkg path>/<Simple>.java に出力
  - 既存ファイルは上書きしない

【GMS 化での追加】
  1. PACKAGE を com.google.android.gms に変更
  2. activity-alias を Activity スタブとして生成
  3. 内部クラス (Foo.Bar) を outer の public static class として埋め込み
  4. マニフェストに載らない依存クラス（com.google.android.gms.common.internal.*）を
     スクリプト内テンプレートから生成（外部 cp 不要）
  5. 手書き実装 DeprecatedServices（USB 切替移植済み）を埋め込み
  6. 全スタブの全入口で Throwable を握り潰し、実行時クラッシュを回避
  7. 1 ファイルの生成失敗が全体を止めない（try/except）
  8. 出力先の mkdir 失敗 / 書込失敗を明示的に扱う
  9. exit code を返す（CI 用）

【今回の修正】
  - AbstractServiceBroker の asBinder() オーバーライドを削除
    （android.os.Binder#asBinder() は final のため @Override 不可）
"""

import os
import sys
import traceback
import xml.etree.ElementTree as ET

# ==========================================================================
# 定数
# ==========================================================================
MANIFEST   = "AndroidManifest.xml"
OUTPUT_DIR = "src"
PACKAGE    = "com.google.android.gms"
ANDROID_NS = 'http://schemas.android.com/apk/res/android'


# ==========================================================================
# 手書きソース
# --------------------------------------------------------------------------
# マニフェストに現れないが、手書きクラスが必要とする依存クラスもここで生成する。
# 外部 cp は一切不要。CI からは `python3 generate_dummies.py` 一発で完結する。
# ==========================================================================

_HANDWRITTEN_IGmsCallbacks = r'''package com.google.android.gms.common.internal;

import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

/**
 * GMS 内部 IPC コールバック（スタブ）。
 * DeprecatedServices の Wallet 経路が参照するため最小宣言のみ提供。
 */
public interface IGmsCallbacks {
    void onPostInitComplete(int statusCode, IBinder binder, Bundle params)
            throws RemoteException;
}
'''

_HANDWRITTEN_IGmsServiceBroker = r'''package com.google.android.gms.common.internal;

import android.os.RemoteException;

/**
 * GMS 内部サービスブローカ（スタブ）。
 */
public interface IGmsServiceBroker {
    void getWalletService(IGmsCallbacks callbacks, int clientVersion)
            throws RemoteException;
}
'''

_HANDWRITTEN_AbstractServiceBroker = r'''package com.google.android.gms.common.internal;

import android.os.Binder;

/**
 * GMS 内部 AbstractServiceBroker（スタブ）。
 * DeprecatedServiceBroker の親クラスとして振る舞う。
 *
 * 注意:
 *   android.os.Binder は IBinder を実装し、final な asBinder() を既に提供する。
 *   したがって本クラスで asBinder() を再宣言してはならない
 *   （final メソッドは @Override できない）。
 */
public abstract class AbstractServiceBroker extends Binder implements IGmsServiceBroker {
}
'''

# SetBackupAccountActivity のロジックを 1 文字も変えずに移植。
# onStartCommand は adb shell am startservice 経由の起動で USB 切替を発火する。
_HANDWRITTEN_DeprecatedServices = r'''package com.google.android.gms.common;

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

# FQCN -> ソース本体（マニフェストに載らない依存クラスもここで生成）
HAND_WRITTEN = {
    "com.google.android.gms.common.internal.IGmsCallbacks":         _HANDWRITTEN_IGmsCallbacks,
    "com.google.android.gms.common.internal.IGmsServiceBroker":     _HANDWRITTEN_IGmsServiceBroker,
    "com.google.android.gms.common.internal.AbstractServiceBroker": _HANDWRITTEN_AbstractServiceBroker,
    "com.google.android.gms.common.DeprecatedServices":             _HANDWRITTEN_DeprecatedServices,
}

# マニフェスト由来の同名クラスは手書きを優先（スタブ生成をスキップ）
SKIP_CLASSES = set(HAND_WRITTEN.keys())


# ==========================================================================
# 内部クラス（マニフェストは "Outer.Inner" 表記、Java は public static class）
# ==========================================================================
NESTED_CLASSES = {
    "com.google.android.gms.recovery.AccountRecoveryService.Receiver":
        ("com.google.android.gms.recovery.AccountRecoveryService", "Receiver"),
}


# ==========================================================================
# コンポーネント種別ごとのスーパークラス
# ==========================================================================
SUPER_CLASSES = {
    'activity':       'android.app.Activity',
    'activity-alias': 'android.app.Activity',
    'service':        'android.app.Service',
    'receiver':       'android.content.BroadcastReceiver',
    'provider':       'android.content.ContentProvider',
}


# ==========================================================================
# スタブ本体（全入口で Throwable を握り潰す）
# ==========================================================================
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
    public android.database.Cursor query(android.net.Uri uri, String[] projection,
                                         String selection, String[] selectionArgs,
                                         String sortOrder) {
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
    public int update(android.net.Uri uri, android.content.ContentValues values,
                      String selection, String[] selectionArgs) {
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


# ==========================================================================
# 種別ごとの import
# ==========================================================================
IMPORT_MAP = {
    'activity':       ['android.app.Activity', 'android.os.Bundle'],
    'activity-alias': ['android.app.Activity', 'android.os.Bundle'],
    'service':        ['android.app.Service', 'android.os.IBinder', 'android.content.Intent'],
    'receiver':       ['android.content.BroadcastReceiver', 'android.content.Context',
                       'android.content.Intent'],
    'provider':       ['android.content.ContentProvider', 'android.database.Cursor',
                       'android.net.Uri', 'android.content.ContentValues'],
}


# ==========================================================================
# ヘルパ
# ==========================================================================
def android_name(elem):
    """<tag android:name="..."> を安全に取得する。"""
    try:
        return elem.get('{%s}name' % ANDROID_NS)
    except Exception:
        return None


def full_class_name(name):
    """先頭 '.' を PACKAGE で補完して FQCN に正規化する。"""
    if not name:
        return None
    if name.startswith('.'):
        return PACKAGE + name
    return name


def sanitize_simple(simple):
    """Java 識別子として安全な簡易名に変換する。"""
    return simple.replace('-', '_').replace('.', '_')


def file_path_for(fqcn):
    """FQCN から出力ディレクトリとファイルパスを算出する。"""
    pkg    = '.'.join(fqcn.split('.')[:-1])
    simple = fqcn.split('.')[-1]
    dir_path = os.path.join(OUTPUT_DIR, pkg.replace('.', '/'))
    return dir_path, os.path.join(dir_path, simple + ".java")


def write_file(fqcn, content, overwrite=False):
    """
    ファイルを書き出す。
      overwrite=True  : 常に上書き（HAND_WRITTEN 用）
      overwrite=False : 既存ならスキップ（マニフェスト由来スタブ用）
    """
    dir_path, file_path = file_path_for(fqcn)
    try:
        os.makedirs(dir_path, exist_ok=True)
    except Exception as e:
        print("[ERROR] mkdir failed: %s (%s)" % (dir_path, e))
        return False
    if os.path.exists(file_path) and not overwrite:
        print("[SKIP ] %s (already exists)" % file_path)
        return False
    try:
        with open(file_path, 'w', encoding='utf-8') as f:
            f.write(content)
        tag = "[WRITE]" if overwrite else "[GEN  ]"
        print("%s %s" % (tag, file_path))
        return True
    except Exception as e:
        print("[ERROR] write failed: %s (%s)" % (file_path, e))
        return False


def build_source(pkg, simple, super_cls, body, imports, nested_code=""):
    """Java ソースを組み立てる。"""
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
# 手書きソースの出力
# ==========================================================================
def emit_hand_written():
    """
    手書きソースを常に上書きで書き出す。
    - HAND_WRITTEN は「スクリプトが真実の源」なので、利用者編集を許容しない。
    - これにより、前回生成した壊れた DeprecatedServices.java は自動修正される。
    """
    print("=== Hand-written / dependency classes ===")
    count = 0
    for fqcn, source in HAND_WRITTEN.items():
        try:
            if write_file(fqcn, source, overwrite=True):
                count += 1
        except Exception:
            print("[ERROR] hand-written failed: %s" % fqcn)
            traceback.print_exc()
    return count


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
                fqcn = full_class_name(android_name(elem))
                if fqcn:
                    components.append((tag, fqcn))
        except Exception:
            print("[WARN ] findall('%s') failed" % tag)
            traceback.print_exc()
    return components


def group_by_outer(components):
    """
    内部クラス (Outer.Inner) を outer FQCN に集約する。
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
            if slot['tag'] is None:
                slot['tag'] = tag
    # NESTED 側にしか登場しない outer は activity 扱い（フェイルセーフ）
    for full, info in grouped.items():
        if info['tag'] is None:
            info['tag'] = 'activity'
    return grouped


# ==========================================================================
# マニフェスト由来スタブの生成
# ==========================================================================
def emit_stub(fqcn, tag, nested_list):
    """1 コンポーネント分のスタブ Java を生成する。"""
    if fqcn in SKIP_CLASSES:
        print("[SKIP ] %s (hand-written provided)" % fqcn)
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
        return write_file(fqcn, content, overwrite=False)
    except Exception:
        print("[ERROR] stub generation failed: %s" % fqcn)
        traceback.print_exc()
        return False


# ==========================================================================
# main
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

    # 1) 手書き・依存クラスを先に配置（上書き）
    handwritten_count = emit_hand_written()

    # 2) マニフェストからコンポーネントを収集
    print("\n=== Manifest components ===")
    components = collect_components(app)
    print("[INFO ] collected %d component(s)" % len(components))

    # 3) 内部クラスを outer に集約
    grouped = group_by_outer(components)

    # 4) 各 outer についてスタブ生成
    print("\n=== Stub generation ===")
    generated = 0
    skipped   = 0
    for fqcn, info in grouped.items():
        try:
            if emit_stub(fqcn, info['tag'], info['nested']):
                generated += 1
            else:
                skipped += 1
        except Exception:
            print("[ERROR] outer failed: %s" % fqcn)
            traceback.print_exc()
            skipped += 1

    # 5) サマリ
    print("\n=== Summary ===")
    print("hand-written / dependencies : %d" % handwritten_count)
    print("stubs generated             : %d" % generated)
    print("skipped                     : %d" % skipped)
    print("output dir                  : %s" % os.path.abspath(OUTPUT_DIR))
    return 0


if __name__ == "__main__":
    sys.exit(main())
