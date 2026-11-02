#!/usr/bin/env python3
"""Package the pinned official Android agent; never download executable code at runtime."""

import hashlib
import io
import struct
import tarfile
import urllib.request
from pathlib import Path


VERSION = "3.7.956-stable"
URL = f"https://cloudpub.ru/download/stable/clo-{VERSION}-android-arm64.tar.gz"
ROOT = Path(__file__).resolve().parents[1]


def validate_android_elf(data):
    if data[:6] != b"\x7fELF\x02\x01":
        raise ValueError("Expected a little-endian ELF64 executable")
    elf_type, machine = struct.unpack_from("<HH", data, 16)
    if elf_type != 3 or machine != 183:
        raise ValueError("Expected an AArch64 position-independent executable")
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 54)
    interpreter = None
    for index in range(phnum):
        offset = phoff + index * phentsize
        if struct.unpack_from("<I", data, offset)[0] == 3:
            start = struct.unpack_from("<Q", data, offset + 8)[0]
            size = struct.unpack_from("<Q", data, offset + 32)[0]
            interpreter = data[start:start + size].rstrip(b"\0")
    if interpreter != b"/system/bin/linker64":
        raise ValueError("Expected Android's linker64, not a Linux glibc/musl executable")


def main():
    with urllib.request.urlopen(URL, timeout=90) as response:
        archive = response.read()
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as bundle:
        binaries = [entry for entry in bundle.getmembers() if entry.isfile() and Path(entry.name).name == "clo"]
        if len(binaries) != 1:
            raise ValueError("Official archive must contain exactly one clo executable")
        # Read just the binary; do not extract archive paths, links or other files.
        data = bundle.extractfile(binaries[0]).read()
    validate_android_elf(data)
    destination = ROOT / "app/src/main/jniLibs/arm64-v8a/libcloudpub.so"
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(data)
    destination.chmod(0o755)
    manifest = ROOT / "release-assets/cloudpub-client.sha256"
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text(
        f"Source: {URL}\n"
        f"Archive SHA-256: {hashlib.sha256(archive).hexdigest()}\n"
        f"Android ELF SHA-256: {hashlib.sha256(data).hexdigest()}\n"
    )
    print(f"Packaged official CloudPub {VERSION} for Android ARM64 ({len(data)} bytes)")


if __name__ == "__main__":
    main()
