#!/usr/bin/env python3
import xml.etree.ElementTree as ET
import os
import re
from collections import defaultdict

MANIFEST = "AndroidManifest.xml"
OUTPUT_DIR = "src"
PACKAGE = "com.google.android.youtube"

ns = {'android': 'http://schemas.android.com/apk/res/android'}

# タグ → スーパークラス（完全修飾名）
SUPER_CLASSES = {
    'activity': 'android.app.Activity',
    'activity-alias': 'android.app.Activity',
    'service': 'android.app.Service',
    'receiver': 'android.content.BroadcastReceiver',
    'provider': 'android.content.ContentProvider'
}

# ContentProvider用のメソッド（onCreateはbooleanを返す）
PROVIDER_METHODS = """
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
"""

# Activity用のonCreate（Bundle引数あり）
ACTIVITY_ONCREATE = """
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }
"""

# Service用のonCreate（引数なし）
SERVICE_ONCREATE = """
    @Override
    public void onCreate() {
        super.onCreate();
    }
"""

# BroadcastReceiver用のonReceive
RECEIVER_ONRECEIVE = """
    @Override
    public void onReceive(Context context, Intent intent) {
    }
"""

def get_full_class_name(name):
    if name.startswith('.'):
        return PACKAGE + name
    return name

def generate_component_class(full_name, super_class, tag, is_inner=False):
    """コンポーネントクラスのソースコードを生成"""
    pkg = '.'.join(full_name.split('.')[:-1])
    simple = full_name.split('.')[-1]
    
    imports = []
    methods = ""
    
    if tag == 'activity' or tag == 'activity-alias':
        imports.extend(['android.app.Activity', 'android.os.Bundle'])
        methods = ACTIVITY_ONCREATE
    elif tag == 'service':
        imports.append('android.app.Service')
        methods = SERVICE_ONCREATE
    elif tag == 'receiver':
        imports.extend(['android.content.BroadcastReceiver', 'android.content.Context', 'android.content.Intent'])
        methods = RECEIVER_ONRECEIVE
    elif tag == 'provider':
        imports.extend(['android.content.ContentProvider', 'android.database.Cursor', 'android.net.Uri', 'android.content.ContentValues'])
        methods = PROVIDER_METHODS
    
    # 重複を除去
    imports = list(set(imports))
    import_lines = '\n'.join([f'import {imp};' for imp in imports if imp != super_class])
    
    if is_inner:
        # 内部クラスは static で、外部クラス内に定義されるため、package宣言とimportは外部クラス側で行う
        # ここではクラス本体のみ返す
        return f"""
    public static class {simple} extends {super_class} {{
{methods}
    }}"""
    else:
        # トップレベルクラス
        return f"""package {pkg};

{import_lines}

public class {simple} extends {super_class} {{
{methods}
}}"""

def main():
    tree = ET.parse(MANIFEST)
    root = tree.getroot()

    app = root.find('application')
    if app is None:
        print("No <application> found")
        return

    # コンポーネントを収集
    components = []
    for tag in ['activity', 'activity-alias', 'service', 'receiver', 'provider']:
        for elem in app.findall(tag):
            name = elem.get('{http://schemas.android.com/apk/res/android}name')
            if name:
                full = get_full_class_name(name)
                components.append((tag, full))

    # ペイロード（YouTubeApplication）は除外
    components = [(t, f) for (t, f) in components if not f.endswith('YouTubeApplication')]

    # 内部クラスをグループ化
    outer_classes = defaultdict(list)  # outer_full_name -> [(inner_full_name, tag), ...]
    standalone = []  # 内部クラスを含まないコンポーネント

    for tag, full in components:
        if '$' in full:
            outer, inner = full.split('$', 1)
            outer_classes[outer].append((inner, tag))
        else:
            standalone.append((tag, full))

    # 内部クラスを含む外部クラスを生成
    for outer_full, inner_list in outer_classes.items():
        pkg = '.'.join(outer_full.split('.')[:-1])
        outer_simple = outer_full.split('.')[-1]
        
        # 外部クラス自体は Activity を継承（ダミーとして）
        outer_super = 'android.app.Activity'
        imports = ['android.app.Activity', 'android.os.Bundle']
        # 内部クラスで必要なimportを追加
        for _, tag in inner_list:
            if tag == 'receiver':
                imports.extend(['android.content.BroadcastReceiver', 'android.content.Context', 'android.content.Intent'])
            elif tag == 'provider':
                imports.extend(['android.content.ContentProvider', 'android.database.Cursor', 'android.net.Uri', 'android.content.ContentValues'])
            elif tag == 'service':
                imports.append('android.app.Service')
        imports = list(set(imports))
        import_lines = '\n'.join([f'import {imp};' for imp in imports if imp != outer_super])
        
        # 内部クラスのコードを生成
        inner_bodies = []
        for inner_name, tag in inner_list:
            # 内部クラスの完全修飾名を仮組み（パッケージは外部と同じ）
            inner_full = f"{pkg}.{outer_simple}${inner_name}"  # 正確には不要
            super_cls = SUPER_CLASSES.get(tag, 'android.app.Activity')
            inner_body = generate_component_class(inner_full, super_cls, tag, is_inner=True)
            inner_bodies.append(inner_body)
        
        inner_code = '\n'.join(inner_bodies)
        
        # 外部クラスのコード
        content = f"""package {pkg};

{import_lines}

public class {outer_simple} extends {outer_super} {{
    {ACTIVITY_ONCREATE}
{inner_code}
}}"""
        
        dir_path = os.path.join(OUTPUT_DIR, pkg.replace('.', '/'))
        os.makedirs(dir_path, exist_ok=True)
        file_path = os.path.join(dir_path, f"{outer_simple}.java")
        with open(file_path, 'w') as f:
            f.write(content)
        print(f"Generated outer: {outer_full} with {len(inner_list)} inner classes")

    # 内部クラスを含まないスタンドアロンクラスを生成
    for tag, full in standalone:
        super_cls = SUPER_CLASSES.get(tag, 'android.app.Activity')
        content = generate_component_class(full, super_cls, tag, is_inner=False)
        pkg = '.'.join(full.split('.')[:-1])
        simple = full.split('.')[-1]
        dir_path = os.path.join(OUTPUT_DIR, pkg.replace('.', '/'))
        os.makedirs(dir_path, exist_ok=True)
        file_path = os.path.join(dir_path, f"{simple}.java")
        with open(file_path, 'w') as f:
            f.write(content)
        print(f"Generated: {full}")

if __name__ == "__main__":
    main()
