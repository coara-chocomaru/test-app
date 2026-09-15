#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import sys
import xml.etree.ElementTree as ET

MANIFEST    = "AndroidManifest.xml"
OUTPUT_DIR  = "src"
DEFAULT_PKG = "com.redbend.client"

SKIP_CLASSES = {
    "com.redbend.client.AdminRequestActivity",
}

ANDROID_NS = "http://schemas.android.com/apk/res/android"

SUPER_CLASSES = {
    "activity": "android.app.Activity",
    "service":  "android.app.Service",
    "receiver": "android.content.BroadcastReceiver",
    "provider": "android.content.ContentProvider",
}

BODY_ACTIVITY = """
    @Override
    protected void onCreate(android.os.Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }
"""

BODY_SERVICE = """
    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public android.os.IBinder onBind(android.content.Intent intent) {
        return null;
    }
"""

BODY_RECEIVER = """
    @Override
    public void onReceive(android.content.Context context, android.content.Intent intent) {
    }
"""

BODY_PROVIDER = """
    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public android.database.Cursor query(android.net.Uri uri,
                                          String[] projection,
                                          String selection,
                                          String[] selectionArgs,
                                          String sortOrder) {
        return null;
    }

    @Override
    public String getType(android.net.Uri uri) {
        return null;
    }

    @Override
    public android.net.Uri insert(android.net.Uri uri,
                                   android.content.ContentValues values) {
        return null;
    }

    @Override
    public int delete(android.net.Uri uri,
                       String selection,
                       String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(android.net.Uri uri,
                       android.content.ContentValues values,
                       String selection,
                       String[] selectionArgs) {
        return 0;
    }
"""

BODY_MAP = {
    "activity": BODY_ACTIVITY,
    "service":  BODY_SERVICE,
    "receiver": BODY_RECEIVER,
    "provider": BODY_PROVIDER,
}

IMPORTS_MAP = {
    "activity": ["android.app.Activity", "android.os.Bundle"],
    "service":  ["android.app.Service", "android.os.IBinder", "android.content.Intent"],
    "receiver": ["android.content.BroadcastReceiver",
                 "android.content.Context",
                 "android.content.Intent"],
    "provider": ["android.content.ContentProvider",
                 "android.database.Cursor",
                 "android.net.Uri",
                 "android.content.ContentValues"],
}

COMPONENT_TAGS = ("activity", "service", "receiver", "provider")


def manifest_package(root):
    pkg = root.get("package")
    return pkg if pkg else DEFAULT_PKG


def qualify(name, pkg):
    if not name:
        return name
    if name.startswith("."):
        return pkg + name
    if "." not in name:
        return pkg + "." + name
    return name


def split_class_hierarchy(full_name):
    parts = full_name.split(".")
    first_class_idx = len(parts) - 1
    for i, part in enumerate(parts):
        if part and part[0].isupper():
            first_class_idx = i
            break
    package = ".".join(parts[:first_class_idx])
    class_chain = parts[first_class_idx:]
    return package, class_chain[:-1], class_chain[-1]


def import_block(tag, super_cls):
    lines = []
    for imp in IMPORTS_MAP.get(tag, []):
        if imp != super_cls:
            lines.append("import " + imp + ";")
    return "\n".join(lines)


def render_simple(pkg, simple, tag):
    super_cls = SUPER_CLASSES[tag]
    body = BODY_MAP[tag]
    return (
        "package " + pkg + ";\n"
        "\n"
        + import_block(tag, super_cls) + "\n"
        "\n"
        "public class " + simple + " extends " + super_cls + " {\n"
        + body + "\n"
        "}\n"
    )


def write_file(rel_path, content):
    full = os.path.join(OUTPUT_DIR, rel_path)
    d = os.path.dirname(full)
    if d and not os.path.isdir(d):
        os.makedirs(d, exist_ok=True)
    if os.path.exists(full):
        print("Skip (already exists): " + full)
        return
    with open(full, "w") as f:
        f.write(content)
    print("Generated: " + full)


def main():
    if not os.path.isfile(MANIFEST):
        print("Manifest not found: " + MANIFEST)
        sys.exit(1)

    tree = ET.parse(MANIFEST)
    root = tree.getroot()
    pkg = manifest_package(root)
    print("Manifest package: " + pkg)

    app = root.find("application")
    if app is None:
        print("No <application> element, aborting")
        sys.exit(1)

    inner_groups = {}

    for tag in COMPONENT_TAGS:
        for elem in app.findall(tag):
            raw = elem.get("{" + ANDROID_NS + "}name")
            if not raw:
                continue
            full = qualify(raw, pkg)

            if full in SKIP_CLASSES:
                print("Skip (manual impl): " + full)
                continue

            comp_pkg, outer_chain, simple = split_class_hierarchy(full)

            if not outer_chain:
                rel = comp_pkg.replace(".", "/") + "/" + simple + ".java"
                write_file(rel, render_simple(comp_pkg, simple, tag))
            else:
                outer = outer_chain[-1]
                key = (comp_pkg, outer)
                inner_groups.setdefault(key, []).append((simple, tag))

    for (comp_pkg, outer), inners in inner_groups.items():
        chunks = []
        used_super = None
        for simple, tag in inners:
            if used_super is None:
                used_super = SUPER_CLASSES[tag]
            body = BODY_MAP[tag]
            chunks.append(
                "    public static class " + simple + " extends "
                + SUPER_CLASSES[tag] + " {\n"
                + body
                + "\n    }\n"
            )

        first_tag = inners[0][1]
        imports_lines = import_block(first_tag, SUPER_CLASSES[first_tag])

        content = (
            "package " + comp_pkg + ";\n"
            "\n"
            + imports_lines + "\n"
            "\n"
            "public class " + outer + " {\n"
            + "\n".join(chunks)
            + "}\n"
        )
        rel = comp_pkg.replace(".", "/") + "/" + outer + ".java"
        write_file(rel, content)

    print("Done.")


if __name__ == "__main__":
    main()
