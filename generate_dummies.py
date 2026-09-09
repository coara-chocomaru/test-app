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

# ----- 各コンポーネントのボディテンプレート -----

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

# ----- 必要な import をタグ別に返す -----

def get_imports(tag):
    imports = []
    if tag in ('activity', 'activity-alias'):
        imports.extend(['android.app.Activity', 'android.os.Bundle'])
    elif tag == 'service':
        imports.extend(['android.app.Service', 'android.os.IBinder', 'android.content.Intent'])
    elif tag == 'receiver':
        imports.extend(['android.content.BroadcastReceiver', 'android.content.Context', 'android.content.Intent'])
    elif tag == 'provider':
        imports.extend(['android.content.ContentProvider', 'android.database.Cursor', 'android.net.Uri', 'android.content.ContentValues'])
    return list(set(imports))

# ----- ユーティリティ -----

def get_full_class_name(name):
    if name.startswith('.'):
        return PACKAGE + name
    return name

def is_inner_class(full_name, component_set):
    """ドットで区切られたクラス名が、内部クラスかを判定（外側のクラスがコンポーネントとして存在するか）"""
    if '.' not in full_name:
        return False, None
    last_dot = full_name.rfind('.')
    prefix = full_name[:last_dot]
    if prefix in component_set:
        return True, prefix
    return False, None

# ----- メイン -----

def main():
    tree = ET.parse(MANIFEST)
    root = tree.getroot()

    app = root.find('application')
    if app is None:
        print("No <application> found")
        return

    # 全コンポーネントを収集
    components = []
    for tag in ['activity', 'activity-alias', 'service', 'receiver', 'provider']:
        for elem in app.findall(tag):
            name = elem.get('{http://schemas.android.com/apk/res/android}name')
            if name:
                full = get_full_class_name(name)
                components.append((tag, full))

    # YouTubeApplication は除外（ペイロードは別途用意）
    components = [(t, f) for (t, f) in components if not f.endswith('YouTubeApplication')]

    # 全クラス名のセット（内部クラス判定用）
    all_names = set(f for _, f in components)

    # 内部クラスとスタンドアロンに分類
    inner_groups = defaultdict(list)   # outer_full -> [(inner_simple, tag), ...]
    standalone = []

    for tag, full in components:
        is_inner, outer = is_inner_class(full, all_names)
        if is_inner:
            inner_simple = full.split('.')[-1]
            inner_groups[outer].append((inner_simple, tag))
        else:
            standalone.append((tag, full))

    # ----- 内部クラスを持つ外部クラスを生成 -----
    for outer_full, inner_list in inner_groups.items():
        pkg = '.'.join(outer_full.split('.')[:-1])
        outer_simple = outer_full.split('.')[-1]

        # 外部クラス自身のタグを取得（あれば）
        outer_tag = None
        for t, f in components:
            if f == outer_full:
                outer_tag = t
                break

        # スーパークラス決定
        if outer_tag is None:
            outer_super = 'android.app.Activity'
        else:
            outer_super = SUPER_CLASSES.get(outer_tag, 'android.app.Activity')

        # import 収集
        all_imports = []
        if outer_tag in ('activity', 'activity-alias') or outer_tag is None:
            all_imports.extend(['android.app.Activity', 'android.os.Bundle'])
        elif outer_tag == 'service':
            all_imports.extend(['android.app.Service', 'android.os.IBinder', 'android.content.Intent'])
        elif outer_tag == 'receiver':
            all_imports.extend(['android.content.BroadcastReceiver', 'android.content.Context', 'android.content.Intent'])
        elif outer_tag == 'provider':
            all_imports.extend(['android.content.ContentProvider', 'android.database.Cursor', 'android.net.Uri', 'android.content.ContentValues'])

        for _, itag in inner_list:
            all_imports.extend(get_imports(itag))
        all_imports = list(set(all_imports))
        import_lines = '\n'.join([f'import {imp};' for imp in all_imports if imp != outer_super])

        # 内部クラスの定義を構築
        inner_defs = []
        for inner_simple, itag in inner_list:
            super_cls = SUPER_CLASSES.get(itag, 'android.app.Activity')
            body = BODY_MAP.get(itag, '')
            inner_defs.append(f"""
    public static class {inner_simple} extends {super_cls} {{
{body}
    }}""")
        inner_code = '\n'.join(inner_defs)

        # 外部クラスのボディ
        outer_body = ""
        if outer_tag in ('activity', 'activity-alias'):
            outer_body = ACTIVITY_BODY
        elif outer_tag == 'service':
            outer_body = SERVICE_BODY
        elif outer_tag == 'receiver':
            outer_body = RECEIVER_BODY
        elif outer_tag == 'provider':
            outer_body = PROVIDER_BODY

        content = f"""package {pkg};

{import_lines}

public class {outer_simple} extends {outer_super} {{
{outer_body}
{inner_code}
}}"""

        dir_path = os.path.join(OUTPUT_DIR, pkg.replace('.', '/'))
        os.makedirs(dir_path, exist_ok=True)
        with open(os.path.join(dir_path, f"{outer_simple}.java"), 'w') as f:
            f.write(content)
        print(f"Generated: {outer_full} (with {len(inner_list)} inner classes)")

    # ----- スタンドアロンクラスを生成 -----
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
