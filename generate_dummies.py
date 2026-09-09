#!/usr/bin/env python3
import xml.etree.ElementTree as ET
import os
import re
from collections import defaultdict

MANIFEST = "AndroidManifest.xml"
OUTPUT_DIR = "src"
PACKAGE = "com.google.android.youtube"

ns = {'android': 'http://schemas.android.com/apk/res/android'}

SUPER_CLASSES = {
    'activity': 'android.app.Activity',
    'activity-alias': 'android.app.Activity',
    'service': 'android.app.Service',
    'receiver': 'android.content.BroadcastReceiver',
    'provider': 'android.content.ContentProvider'
}

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
    if name.startswith('.'):
        return PACKAGE + name
    return name

def main():
    tree = ET.parse(MANIFEST)
    root = tree.getroot()

    app = root.find('application')
    if app is None:
        print("No <application> found")
        return

    # コンポーネントを収集（タグと完全修飾名のセット）
    components = set()
    for tag in ['activity', 'activity-alias', 'service', 'receiver', 'provider']:
        for elem in app.findall(tag):
            name = elem.get('{http://schemas.android.com/apk/res/android}name')
            if name:
                full = get_full_class_name(name)
                components.add((tag, full))

    # ペイロード（YouTubeApplication）は除く
    components = {c for c in components if not c[1].endswith('YouTubeApplication')}

    # 通常クラス（内部クラスを含まない）と内部クラスを分離
    normal_classes = defaultdict(list)   # 外部クラス名 -> [(inner_class_name, is_provider), ...]
    standalone_classes = []              # 内部クラスを持たない通常クラス

    for tag, full_name in components:
        if '$' in full_name:
            outer, inner = full_name.split('$', 1)
            normal_classes[outer].append((inner, tag == 'provider'))
        else:
            standalone_classes.append((full_name, tag == 'provider'))

    # 内部クラスを含まない通常クラスを出力
    for full_name, is_provider in standalone_classes:
        pkg = '.'.join(full_name.split('.')[:-1])
        simple = full_name.split('.')[-1]
        super_cls = 'android.app.Activity'  # デフォルト、実際はタグから決定できるが簡略化
        # タグ情報が失われているので、Providerだけ特別扱い
        if is_provider:
            super_cls = 'android.content.ContentProvider'
            methods = PROVIDER_METHODS
        else:
            super_cls = 'android.app.Activity'
            methods = ''
        content = f"""package {pkg};

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.database.Cursor;
import android.net.Uri;
import android.content.ContentValues;

public class {simple} extends {super_cls} {{
    @Override
    public void onCreate() {{
        super.onCreate();
    }}
{methods}
}}"""
        dir_path = os.path.join(OUTPUT_DIR, pkg.replace('.', '/'))
        os.makedirs(dir_path, exist_ok=True)
        with open(os.path.join(dir_path, f"{simple}.java"), 'w') as f:
            f.write(content)
        print(f"Generated: {full_name}")

    # 内部クラスを持つ外部クラスを出力（1ファイルにまとめる）
    for outer_full, inner_list in normal_classes.items():
        pkg = '.'.join(outer_full.split('.')[:-1])
        outer_simple = outer_full.split('.')[-1]
        # 外部クラスのスーパークラスは Object（直接コンポーネントとして使われない前提）
        # ただし、外部クラス自体もコンポーネントとして使われる可能性があるので Activity にする
        # ここでは、マニフェストに外部クラス名が登場していない場合が多いので Object で十分
        # しかし安全のため Activity を継承させておく
        outer_super = 'android.app.Activity'
        # 内部クラス定義を構築
        inner_defs = []
        for inner, is_provider in inner_list:
            super_cls = 'android.content.ContentProvider' if is_provider else 'android.app.Activity'
            methods = PROVIDER_METHODS if is_provider else ''
            inner_defs.append(f"""
    public static class {inner} extends {super_cls} {{
        @Override
        public void onCreate() {{
            super.onCreate();
        }}
{methods}
    }}""")
        body = "\n".join(inner_defs)
        content = f"""package {pkg};

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.database.Cursor;
import android.net.Uri;
import android.content.ContentValues;

public class {outer_simple} extends {outer_super} {{
{body}
}}"""
        dir_path = os.path.join(OUTPUT_DIR, pkg.replace('.', '/'))
        os.makedirs(dir_path, exist_ok=True)
        with open(os.path.join(dir_path, f"{outer_simple}.java"), 'w') as f:
            f.write(content)
        print(f"Generated outer: {outer_full} with {len(inner_list)} inner classes")

if __name__ == "__main__":
    main()
