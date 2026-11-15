#!/usr/bin/env python3
"""Fail a release if bundled native libraries do not cover common 32/64-bit ABIs."""
import sys
import zipfile
from pathlib import PurePosixPath

REQUIRED_ABIS = ("armeabi-v7a", "arm64-v8a", "x86", "x86_64")


def verify(apk):
    with zipfile.ZipFile(apk) as archive:
        libraries = set()
        for entry in archive.namelist():
            if not entry.startswith("lib/") or not entry.endswith(".so"):
                continue
            parts = PurePosixPath(entry).parts
            if len(parts) != 3 or not parts[2].startswith("lib"):
                raise ValueError(f"Unexpected native library path: {entry}")
            libraries.add((parts[1], parts[2]))
    if not libraries:
        print("No packaged native libraries; APK bytecode is not ABI-specific.")
        return
    names = sorted({name for _, name in libraries})
    missing = [f"lib/{abi}/{name}" for name in names for abi in REQUIRED_ABIS if (abi, name) not in libraries]
    if missing:
        raise ValueError("Missing 32/64-bit native libraries: " + ", ".join(missing))
    print("All native libraries cover armeabi-v7a, arm64-v8a, x86 and x86_64.")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit("Usage: python3 verify_apk_abis.py path/to/app.apk")
    try:
        verify(sys.argv[1])
    except (OSError, zipfile.BadZipFile, ValueError) as error:
        sys.exit(str(error))
