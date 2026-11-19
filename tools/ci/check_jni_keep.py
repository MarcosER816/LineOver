#!/usr/bin/env python3
"""Fails when R8 dropped a Kotlin method that native code looks up by name.

jni_bridge.cpp calls NativeEngine methods back with GetMethodID. Nothing in Kotlin calls
them, so only a keep rule (core/engine/consumer-rules.pro) stops R8 from removing them, and
the emulator tests run the unminified debug build. This reads every GetMethodID in the
engine's JNI code and checks the release build's R8 seeds (members kept by rules).

Usage: check_jni_keep.py [seeds.txt]  (default: the app's release mapping output)
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
JNI_DIR = ROOT / "core/engine-native/src/main/cpp/android"
CLASS = "dev.mark.lineover.core.engine.NativeEngine"
PRIMITIVES = {"Z": "boolean", "B": "byte", "C": "char", "S": "short", "I": "int", "J": "long", "F": "float", "D": "double", "V": "void"}


def java_types(signature: str) -> list[str]:
    """JNI type descriptors ("[I", "Z", "Ljava/lang/String;") as Java type names."""
    out, i = [], 0
    while i < len(signature):
        dims = 0
        while signature[i] == "[":
            dims, i = dims + 1, i + 1
        if signature[i] == "L":
            end = signature.index(";", i)
            name, i = signature[i + 1 : end].replace("/", "."), end + 1
        else:
            name, i = PRIMITIVES[signature[i]], i + 1
        out.append(name + "[]" * dims)
    return out


def main() -> int:
    seeds_path = pathlib.Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / "app/build/outputs/mapping/release/seeds.txt"
    seeds = set(seeds_path.read_text().splitlines())
    lookups = []
    for source in sorted(JNI_DIR.glob("*.cpp")):
        lookups += re.findall(r'GetMethodID\([^,]+,\s*"(\w+)",\s*"\(([^)]*)\)([^"]+)"\)', source.read_text())
    if not lookups:
        print("check_jni_keep: no GetMethodID found; is the JNI directory right?")
        return 1
    missing = []
    for name, args, ret in lookups:
        entry = f"{CLASS}: {java_types(ret)[0]} {name}({','.join(java_types(args))})"
        print(("ok      " if entry in seeds else "MISSING ") + entry)
        if entry not in seeds:
            missing.append(entry)
    if missing:
        print("R8 does not keep these JNI callbacks: add them to core/engine/consumer-rules.pro")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
