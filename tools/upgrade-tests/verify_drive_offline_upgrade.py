#!/usr/bin/env python3
"""Non-destructive emulator-only in-place upgrade regression check for Drive offline audio.

Workflow:
  1. Install an OLDER, correctly signed Counters APK on a disposable emulator.
  2. Authorize a TEST Drive account and download at least one TEST audio file.
  3. python3 tools/upgrade-tests/verify_drive_offline_upgrade.py baseline --state /tmp/drive-before.json
  4. python3 tools/upgrade-tests/verify_drive_offline_upgrade.py upgrade --state /tmp/drive-before.json --apk /path/to/NEW-SIGNED.apk

Never targets a physical device. Never clears app data. Never installs an APK without
matching the pinned package and SHA-1 certificate.
"""
import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

PACKAGE = "com.palmerintech.firetube.drivefavoritescounterspreview"
EXPECTED_SHA1 = "A4:1B:36:91:38:EB:A1:D5:0E:23:BF:11:09:55:BD:38:6B:EB:2A:68"
OFFLINE_ROOT = "no_backup/drive-library"


def cmd(*args, binary=False):
    p = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if p.returncode:
        raise RuntimeError(f"Command failed ({p.returncode}): {args[0]} {args[1] if len(args)>1 else ''}: {p.stderr.decode(errors='replace')[:500]}")
    return p.stdout if binary else p.stdout.decode(errors="replace").strip()


def adb(*args, binary=False):
    return cmd("adb", *args, binary=binary)


def check_emulator():
    devices = cmd("adb", "devices").splitlines()[1:]
    active = [line.split()[0] for line in devices if line.endswith("\tdevice")]
    if len(active) != 1:
        raise RuntimeError("Exactly one online Android emulator is required; disconnect other devices")
    if adb("-s", active[0], "shell", "getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("Refusing to run on a physical phone")
    return active[0]


def snapshot(serial):
    # run-as is supported only by debuggable builds; fail rather than root or bypass it.
    command = ["-s", serial, "exec-out", "run-as", PACKAGE]
    listing = adb(*command, "sh", "-c", f"find {OFFLINE_ROOT} -type f", binary=False)
    paths = sorted(set(line.strip() for line in listing.splitlines() if line.strip()))
    paths = [p for p in paths if p.startswith(OFFLINE_ROOT + "/") and ".." not in p.split("/")]
    audio = [p for p in paths if re.search(r"/audio-[a-f0-9]{64}\\.bin$", p)]
    if not audio:
        raise RuntimeError("No Drive offline audio found. Download a test file in the OLD version before baseline.")
    values = {}
    for path in paths:
        data = adb(*command, "cat", path, binary=True)
        values[path] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    return values


def inspect_apk(apk):
    if not apk.is_file():
        raise RuntimeError("Signed APK file does not exist")
    if shutil.which("aapt") is None or shutil.which("apksigner") is None:
        raise RuntimeError("aapt and apksigner from Android SDK build-tools are required")
    badging = cmd("aapt", "dump", "badging", str(apk))
    m = re.search(r"package: name='([^']+)' versionCode='(\\d+)'", badging)
    if not m or m.group(1) != PACKAGE:
        raise RuntimeError("Unexpected APK package")
    cert = cmd("apksigner", "verify", "--print-certs", str(apk))
    m = re.search(r"Signer #1 certificate SHA-1 digest:\\s*([0-9a-fA-F:]+)", cert)
    if not m:
        raise RuntimeError("Could not verify APK signing certificate")
    actual = m.group(1).replace(":", "").upper()
    if actual != EXPECTED_SHA1.replace(":", ""):
        raise RuntimeError("APK signature does not match installed Counters identity")
    return int(re.search(r"versionCode='(\\d+)'", badging).group(1))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=["baseline", "upgrade"])
    parser.add_argument("--state", type=Path, required=True)
    parser.add_argument("--apk", type=Path)
    args = parser.parse_args()
    serial = check_emulator()
    if args.phase == "baseline":
        if args.state.exists():
            raise RuntimeError("Baseline already exists; refusing to overwrite evidence")
        before = snapshot(serial)
        args.state.write_text(json.dumps({"package": PACKAGE, "files": before}, indent=2))
        print(f"BASELINE SAVED: {len(before)} offline-related files, including audio")
        return
    if args.apk is None:
        raise RuntimeError("--apk required for upgrade")
    original = json.loads(args.state.read_text())
    if original["package"] != PACKAGE:
        raise RuntimeError("Wrong baseline package")
    before = original["files"]
    current = snapshot(serial)
    if before != current:
        raise RuntimeError("Offline files already changed BEFORE upgrade; refusing to continue")
    version = inspect_apk(args.apk)
    print(f"Upgrading disposable emulator to versionCode={version} with pinned signing certificate")
    adb("-s", serial, "install", "-r", str(args.apk))
    after = snapshot(serial)
    if after != before:
        added = sorted(set(after) - set(before))
        missing = sorted(set(before) - set(after))
        changed = sorted(p for p in set(after) & set(before) if after[p] != before[p])
        raise RuntimeError(f"FAIL: offline files changed: added={added}, missing={missing}, modified={changed}")
    print(f"PASS: {len(before)} files preserved byte-for-byte after in-place APK update")
    print("NOTE: Manually confirm offline playback in airplane mode on the emulator.")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        sys.exit(1)
