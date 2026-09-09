#!/usr/bin/env python3
import xml.etree.ElementTree as ET
import os
import re
from collections import defaultdict

MANIFEST = "AndroidManifest.xml"
OUTPUT_DIR = "src"  # 生成されたJavaファイルの出力先
PACKAGE = "com.google.android.youtube"

ns = {'android': 'http://schemas.android.com/apk/res/android'}

# コンポーネントの種類とスーパークラスのマッピング
SUPER_CLASSES = {
    'activity': 'android.app.Activity',
    'activity-alias': 'android.app.Activity',
    'service': 'android.app.Service',
    'receiver': 'android.content.BroadcastReceiver',
    'provider': 'android.content.ContentProvider'
}

# ダミーメソッドのテンプレート（Provider用）
PROVIDER_METHODS = """
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) { return null; }
    @Override
    public String getType(Uri uri) { return null; }
    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
"""

def get_full_class_name(name):
    """android:name を完全修飾クラス名に変換（.で始まる場合はパッケージを付与）"""
    if name.startswith('.'):
        return PACKAGE + name
    return name

def parse_component(name, tag):
    """クラス名を外部クラスと内部クラスに分割"""
    if '$' in name:
        outer, inner = name.split('$', 1)
        return outer, inner
    else:
        return name, None

def generate_java_file(full_class_name, super_class, is_provider=False):
    """Javaファイルを生成（内部クラス対応）"""
    if '$' in full_class_name:
        outer_cls, inner_cls = full_class_name.split('$', 1)
        pkg = '.'.join(outer_cls.split('.')[:-1])
        outer_simple = outer_cls.split('.')[-1]
        # 内部クラスは static nested として定義
        content = f"""package {pkg};

import {super_class};
import android.content.Intent;
import android.os.Bundle;
import android.database.Cursor;
import android.net.Uri;
import android.content.ContentValues;

public class {outer_simple} {{
    public static class {inner_cls} extends {super_class} {{
        @Override
        public void onCreate() {{
            super.onCreate();
        }}
"""
        if is_provider:
            content += PROVIDER_METHODS
        content += "    }\n}"
        return (pkg, outer_simple, content)
    else:
        pkg = '.'.join(full_class_name.split('.')[:-1])
        simple = full_class_name.split('.')[-1]
        content = f"""package {pkg};

import {super_class};
import android.content.Intent;
import android.os.Bundle;
import android.database.Cursor;
import android.net.Uri;
import android.content.ContentValues;

public class {simple} extends {super_class} {{
    @Override
    public void onCreate() {{
        super.onCreate();
    }}
"""
        if is_provider:
            content += PROVIDER_METHODS
        content += "}"
        return (pkg, simple, content)

def main():
    tree = ET.parse(MANIFEST)
    root = tree.getroot()

    # アプリケーション要素を取得
    app = root.find('application')
    if app is None:
        print("No <application> found")
        return

    # 全てのコンポーネントを収集（重複を避ける）
    components = set()
    for tag in ['activity', 'activity-alias', 'service', 'receiver', 'provider']:
        for elem in app.findall(tag):
            name = elem.get('{http://schemas.android.com/apk/res/android}name')
            if name:
                full = get_full_class_name(name)
                components.add((tag, full))

    # 既存のペイロード（YouTubeApplication）は含めない（ユーザー提供）
    # ただし、マニフェストに YouTubeApplication が指定されているので、それは除外
    components = {c for c in components if not c[1].endswith('YouTubeApplication')}

    # 生成済みの外部クラスを管理（内部クラス用）
    outer_classes = defaultdict(list)

    for tag, full_name in sorted(components):
        super_cls = SUPER_CLASSES.get(tag, 'android.app.Activity')
        is_provider = (tag == 'provider')
        pkg, simple, content = generate_java_file(full_name, super_cls, is_provider)

        if '$' in full_name:
            # 内部クラス：outer_classes に追加（後でまとめて出力）
            outer, inner = full_name.split('$', 1)
            outer_classes[outer].append((inner, content))
        else:
            # 通常クラス：即座に出力
            dir_path = os.path.join(OUTPUT_DIR, pkg.replace('.', '/'))
            os.makedirs(dir_path, exist_ok=True)
            file_path = os.path.join(dir_path, f"{simple}.java")
            with open(file_path, 'w') as f:
                f.write(content)
            print(f"Generated: {full_name}")

    # 内部クラスを外部クラスファイルにまとめて出力
    for outer_full, inner_classes in outer_classes.items():
        pkg = '.'.join(outer_full.split('.')[:-1])
        outer_simple = outer_full.split('.')[-1]
        # 外部クラスのスーパークラスは特に不要なので Object を継承
        # ただし、マニフェストで外部クラス自体がコンポーネントとして使われることは少ないが、念のため
        body = "\n".join([f"    {content}" for _, content in inner_classes])
        outer_content = f"""package {pkg};

public class {outer_simple} {{
{body}
}}"""
        dir_path = os.path.join(OUTPUT_DIR, pkg.replace('.', '/'))
        os.makedirs(dir_path, exist_ok=True)
        file_path = os.path.join(dir_path, f"{outer_simple}.java")
        with open(file_path, 'w') as f:
            f.write(outer_content)
        print(f"Generated outer class: {outer_full} (with {len(inner_classes)} inner classes)")

if __name__ == "__main__":
    main()
