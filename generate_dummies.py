#!/usr/bin/env python3
import xml.etree.ElementTree as ET
import os
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

# ---------- 各コンポーネント用のメソッドテンプレート ----------

ACTIVITY_BODY = """
    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }
"""

SERVICE_BODY = """
    @Override
    public void onCreate() {
        super.onCreate();
    }
    @Override
    public android.os.IBinder onBind(android.content.Intent intent) {
        return null;
    }
"""

RECEIVER_BODY = """
    @Override
    public void onReceive(android.content.Context context, android.content.Intent intent) {
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
    'activity': ACTIVITY_BODY,
    'activity-alias': ACTIVITY_BODY,
    'service': SERVICE_BODY,
    'receiver': RECEIVER_BODY,
    'provider': PROVIDER_BODY
}

# ---------- ユーティリティ ----------

def get_full_class_name(name):
    if name.startswith('.'):
        return PACKAGE + name
    return name

def get_imports(tag):
    """タグに応じて必要な import を返す"""
    imports = []
    if tag in ('activity', 'activity-alias'):
        imports.append('android.app.Activity')
        imports.append('android.os.Bundle')
    elif tag == 'service':
        imports.append('android.app.Service')
        imports.append('android.os.IBinder')
        imports.append('android.content.Intent')
    elif tag == 'receiver':
        imports.append('android.content.BroadcastReceiver')
        imports.append('android.content.Context')
        imports.append('android.content.Intent')
    elif tag == 'provider':
        imports.append('android.content.ContentProvider')
        imports.append('android.database.Cursor')
        imports.append('android.net.Uri')
        imports.append('android.content.ContentValues')
    return list(set(imports))

# ---------- メイン ----------

def main():
    tree = ET.parse(MANIFEST)
    root = tree.getroot()

    app = root.find('application')
    if app is None:
        print("No <application> found")
        return

    # 全コンポーネントを収集（タグ, 完全修飾名）
    components = []
    for tag in ['activity', 'activity-alias', 'service', 'receiver', 'provider']:
        for elem in app.findall(tag):
            name = elem.get('{http://schemas.android.com/apk/res/android}name')
            if name:
                full = get_full_class_name(name)
                components.append((tag, full))

    # YouTubeApplication は除外（ユーザーが別途用意）
    components = [(t, f) for (t, f) in components if not f.endswith('YouTubeApplication')]

    # 内部クラス（$を含む）とスタンドアロンクラスに分離
    inner_groups = defaultdict(list)   # outer_full -> [(inner_simple, tag), ...]
    standalone = []

    for tag, full in components:
        if '$' in full:
            outer, inner = full.split('$', 1)
            inner_groups[outer].append((inner, tag))
        else:
            standalone.append((tag, full))

    # ---------- 内部クラスを含む外部クラスを生成 ----------
    for outer_full, inner_list in inner_groups.items():
        pkg = '.'.join(outer_full.split('.')[:-1])
        outer_simple = outer_full.split('.')[-1]

        # 外部クラス自体は Activity を継承（ダミーとして）
        outer_super = 'android.app.Activity'
        all_imports = ['android.app.Activity', 'android.os.Bundle']

        for _, tag in inner_list:
            all_imports.extend(get_imports(tag))
        all_imports = list(set(all_imports))
        import_lines = '\n'.join([f'import {imp};' for imp in all_imports if imp != outer_super])

        # 内部クラスの定義を構築
        inner_defs = []
        for inner_name, tag in inner_list:
            super_cls = SUPER_CLASSES.get(tag, 'android.app.Activity')
            body = BODY_MAP.get(tag, '')
            inner_defs.append(f"""
    public static class {inner_name} extends {super_cls} {{
{body}
    }}""")

        inner_code = '\n'.join(inner_defs)
        content = f"""package {pkg};

{import_lines}

public class {outer_simple} extends {outer_super} {{
{ACTIVITY_BODY}
{inner_code}
}}"""

        dir_path = os.path.join(OUTPUT_DIR, pkg.replace('.', '/'))
        os.makedirs(dir_path, exist_ok=True)
        with open(os.path.join(dir_path, f"{outer_simple}.java"), 'w') as f:
            f.write(content)
        print(f"Generated: {outer_full} (with {len(inner_list)} inner classes)")

    # ---------- スタンドアロンクラスを生成 ----------
    for tag, full in standalone:
        pkg = '.'.join(full.split('.')[:-1])
        simple = full.split('.')[-1]
        super_cls = SUPER_CLASSES.get(tag, 'android.app.Activity')
        body = BODY_MAP.get(tag, '')
        imports = get_imports(tag)
        import_lines = '\n'.join([f'import {imp};' for imp in imports if imp != super_cls])

        content = f"""package {pkg};

{import_lines}

public class {simple} extends {super_cls} {{
{body}
}}"""

        dir_path = os.path.join(OUTPUT_DIR, pkg.replace('.', '/'))
        os.makedirs(dir_path, exist_ok=True)
        with open(os.path.join(dir_path, f"{simple}.java"), 'w') as f:
            f.write(content)
        print(f"Generated: {full}")

if __name__ == "__main__":
    main()
