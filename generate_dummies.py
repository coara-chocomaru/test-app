#!/usr/bin/env python3
import xml.etree.ElementTree as ET
import os

MANIFEST = "AndroidManifest.xml"
OUTPUT_DIR = "src"
PACKAGE = "com.google.android.backup"

# 手動で提供するクラス（スキップ）
SKIP_CLASSES = [
    "com.google.android.backup.BackupTransportService",  # 以前エラーになったが、今回は不要
    "com.google.android.backup.SetBackupAccountActivity" # カスタム実装を使用
]

ns = {'android': 'http://schemas.android.com/apk/res/android'}

SUPER_CLASSES = {
    'activity': 'android.app.Activity',
    'service': 'android.app.Service',
    'receiver': 'android.content.BroadcastReceiver',
    'provider': 'android.content.ContentProvider'
}

# 各コンポーネントのボディ（最小限）
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
    'service': SERVICE_BODY,
    'receiver': RECEIVER_BODY,
    'provider': PROVIDER_BODY
}

def get_full_class_name(name):
    if name.startswith('.'):
        return PACKAGE + name
    return name

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

def generate_class(full_name, tag):
    if full_name in SKIP_CLASSES:
        print(f"Skipping {full_name} (manual file provided)")
        return None

    pkg = '.'.join(full_name.split('.')[:-1])
    simple = full_name.split('.')[-1]
    super_cls = SUPER_CLASSES.get(tag, 'android.app.Activity')
    body = BODY_MAP.get(tag, '')
    imports = get_imports(tag)
    import_lines = '\n'.join([f'import {imp};' for imp in imports if imp != super_cls])

    content = f"""package {pkg};

{import_lines}

public class {simple} extends {super_cls} {{
{body}
}}"""
    return content

def main():
    tree = ET.parse(MANIFEST)
    root = tree.getroot()

    app = root.find('application')
    if app is None:
        print("No <application> found")
        return

    components = []
    for tag in ['activity', 'service', 'receiver', 'provider']:
        for elem in app.findall(tag):
            name = elem.get('{http://schemas.android.com/apk/res/android}name')
            if name:
                full = get_full_class_name(name)
                components.append((tag, full))

    for tag, full in components:
        content = generate_class(full, tag)
        if content is None:
            continue
        pkg = '.'.join(full.split('.')[:-1])
        simple = full.split('.')[-1]
        dir_path = os.path.join(OUTPUT_DIR, pkg.replace('.', '/'))
        os.makedirs(dir_path, exist_ok=True)
        file_path = os.path.join(dir_path, f"{simple}.java")
        if os.path.exists(file_path):
            print(f"File {file_path} already exists, skipping")
        else:
            with open(file_path, 'w') as f:
                f.write(content)
            print(f"Generated: {full}")

if __name__ == "__main__":
    main()
