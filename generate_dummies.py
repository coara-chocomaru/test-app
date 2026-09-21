#!/usr/bin/env python3
import xml.etree.ElementTree as ET
import os

MANIFEST   = "AndroidManifest.xml"
OUTPUT_DIR = "src"
PACKAGE    = "com.google.android.gms"

# 手動提供（改修済み）— 生成しない
SKIP_CLASSES = {
    "com.google.android.gms.common.DeprecatedServices",
}

# マニフェスト上で . 区切りだが Java では内部クラスとして書くもの
# key: FQCN (manifest), value: (outer_FQCN, inner_simple)
NESTED_CLASSES = {
    "com.google.android.gms.recovery.AccountRecoveryService.Receiver":
        ("com.google.android.gms.recovery.AccountRecoveryService", "Receiver"),
}

ns = {'android': 'http://schemas.android.com/apk/res/android'}

SUPER_CLASSES = {
    'activity':       'android.app.Activity',
    'activity-alias': 'android.app.Activity',
    'service':        'android.app.Service',
    'receiver':       'android.content.BroadcastReceiver',
    'provider':       'android.content.ContentProvider',
}

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
    'activity':       ACTIVITY_BODY,
    'activity-alias': ACTIVITY_BODY,
    'service':        SERVICE_BODY,
    'receiver':       RECEIVER_BODY,
    'provider':       PROVIDER_BODY,
}


def get_full_class_name(name: str) -> str:
    if name.startswith('.'):
        return PACKAGE + name
    return name


def get_imports(tag: str):
    if tag in ('activity', 'activity-alias'):
        return ['android.app.Activity', 'android.os.Bundle']
    if tag == 'service':
        return ['android.app.Service', 'android.os.IBinder', 'android.content.Intent']
    if tag == 'receiver':
        return ['android.content.BroadcastReceiver', 'android.content.Context', 'android.content.Intent']
    if tag == 'provider':
        return ['android.content.ContentProvider', 'android.database.Cursor',
                'android.net.Uri', 'android.content.ContentValues']
    return []


def build_source(pkg, simple, super_cls, body, imports, nested_code=""):
    filtered = sorted({imp for imp in imports if imp != super_cls})
    import_lines = '\n'.join(f'import {imp};' for imp in filtered)
    return f"""package {pkg};

{import_lines}

public class {simple} extends {super_cls} {{
{body}
{nested_code}
}}"""


def write_file(full_name, content):
    pkg    = '.'.join(full_name.split('.')[:-1])
    simple = full_name.split('.')[-1]
    dir_path = os.path.join(OUTPUT_DIR, pkg.replace('.', '/'))
    os.makedirs(dir_path, exist_ok=True)
    file_path = os.path.join(dir_path, f"{simple}.java")
    if os.path.exists(file_path):
        print(f"File {file_path} already exists, skipping")
        return False
    with open(file_path, 'w') as f:
        f.write(content)
    print(f"Generated: {full_name}")
    return True


def main():
    tree = ET.parse(MANIFEST)
    root = tree.getroot()
    app  = root.find('application')
    if app is None:
        print("No <application> found")
        return

    # (tag, FQCN) をすべて収集
    components = []
    for tag in ('activity', 'activity-alias', 'service', 'receiver', 'provider'):
        for elem in app.findall(tag):
            name = elem.get('{http://schemas.android.com/apk/res/android}name')
            if name:
                components.append((tag, get_full_class_name(name)))

    # outer FQCN ごとにグルーピング
    grouped = {}
    for tag, full in components:
        if full in NESTED_CLASSES:
            outer, inner_simple = NESTED_CLASSES[full]
            slot = grouped.setdefault(outer, {'tag': None, 'nested': []})
            slot['nested'].append((tag, inner_simple))
        else:
            slot = grouped.setdefault(full, {'tag': None, 'nested': []})
            slot['tag'] = tag

    # NESTED 側にしか現れない outer は activity 扱い（通常発生しないがフェイルセーフ）
    for full, info in grouped.items():
        if info['tag'] is None:
            info['tag'] = 'activity'

    for full, info in grouped.items():
        if full in SKIP_CLASSES:
            print(f"Skipping {full} (manual file provided)")
            continue

        tag       = info['tag']
        super_cls = SUPER_CLASSES.get(tag, 'android.app.Activity')
        body      = BODY_MAP.get(tag, '')
        imports   = list(get_imports(tag))

        nested_code = ""
        for nested_tag, nested_simple in info['nested']:
            nested_super = SUPER_CLASSES.get(nested_tag, 'android.content.BroadcastReceiver')
            nested_body  = BODY_MAP.get(nested_tag, '')
            imports.extend(get_imports(nested_tag))
            nested_code += f"""
    public static class {nested_simple} extends {nested_super} {{
{nested_body}
    }}
"""

        pkg    = '.'.join(full.split('.')[:-1])
        simple = full.split('.')[-1]
        content = build_source(pkg, simple, super_cls, body, imports, nested_code)
        write_file(full, content)


if __name__ == "__main__":
    main()
