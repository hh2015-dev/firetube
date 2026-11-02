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
import shlex
import tempfile
import subprocess
import sys
from pathlib import Path

PACKAGE = "com.palmerintech.firetube.drivefavoritescounterspreview"
EXPECTED_SHA1 = "A4:1B:36:91:38:EB:A1:D5:0E:23:BF:11:09:55:BD:38:6B:EB:2A:68"
EXPECTED_SHA256 = "CFE89608EDD0944E0C0D571784D6B0554AAD8AE5BAEA7968BF0DEFD956CADE12"
OFFLINE_ROOT = "no_backup/drive-library"


def cmd(*args, binary=False):
    p = subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)
    if p.returncode:
        raise RuntimeError(f"Command failed ({p.returncode}): {Path(args[0]).name}; raw device diagnostics suppressed")
    return p.stdout if binary else p.stdout.decode(errors="replace").strip()


def adb(*args, binary=False):
    return cmd("adb", *args, binary=binary)


def check_emulator():
    devices = cmd("adb", "devices").splitlines()[1:]
    active = [line.split()[0] for line in devices if line.endswith("\tdevice")]
    if len(active) != 1:
        raise RuntimeError("Exactly one online Android emulator is required; disconnect other devices")
    if not active[0].startswith("emulator-") or adb("-s", active[0], "shell", "getprop", "ro.kernel.qemu") != "1":
        raise RuntimeError("Refusing to run on a physical phone")
    return active[0]


def installed_version(serial):
    """Require an installed package and extract its Android versionCode."""
    output = adb("-s", serial, "shell", "dumpsys", "package", PACKAGE)
    m = re.search(r"\bversionCode=(\d+)\b", output)
    if not m:
        raise RuntimeError("Could not verify installed Counters versionCode")
    return int(m.group(1))


def snapshot(serial):
    # run-as is supported only by debuggable builds; fail rather than root or bypass it.
    def read_as(*args):
        # adb sends a remote shell command; preserve sh -c and file-path boundaries.
        remote = shlex.join(["run-as", PACKAGE, *args])
        return adb("-s", serial, "exec-out", remote, binary=True)
    listing = read_as("find", OFFLINE_ROOT, "-type", "f", "-print0")
    if isinstance(listing, str):
        listing = listing.encode()
    paths = sorted(set(p.decode("utf-8") for p in listing.split(b"\0") if p))
    if any(not p.startswith(OFFLINE_ROOT + "/") or ".." in p.split("/") for p in paths):
        raise RuntimeError("Unexpected offline file path; refusing incomplete evidence")
    audio = [p for p in paths if re.search(r"/audio-[a-f0-9]{64}\.bin$", p)]
    if not audio:
        raise RuntimeError("No Drive offline audio found. Download a test file in the OLD version before baseline.")
    values = {}
    for path in paths:
        data = read_as("cat", path)
        if path in audio and not data:
            raise RuntimeError("Offline audio is empty; baseline is not playable evidence")
        values[path] = {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
    return values


def inspect_apk(apk):
    if not apk.is_file():
        raise RuntimeError("Signed APK file does not exist")
    if shutil.which("aapt") is None or shutil.which("apksigner") is None:
        raise RuntimeError("aapt and apksigner from Android SDK build-tools are required")
    badging = cmd("aapt", "dump", "badging", str(apk))
    m = re.search(r"package: name='([^']+)' versionCode='(\d+)'", badging)
    if not m or m.group(1) != PACKAGE:
        raise RuntimeError("Unexpected APK package")
    cert = cmd("apksigner", "verify", "--verbose", "--print-certs", str(apk))
    if not re.search(r"(?m)^Number of signers: 1\s*$", cert):
        raise RuntimeError("Expected exactly one APK signer")
    # Android SDK versions label this either Signer #1 or V3.0 Signer:.
    for label, expected in [("SHA-1", EXPECTED_SHA1.replace(":", "")), ("SHA-256", EXPECTED_SHA256)]:
        found = re.findall(r"(?m)^.*\bcertificate " + label + r" digest:\s*([0-9a-fA-F:]+)\s*$", cert)
        if not found or {v.replace(":", "").upper() for v in found} != {expected}:
            raise RuntimeError("APK signature does not match installed Counters identity")
    if "Verified using v2 scheme (APK Signature Scheme v2): true" not in cert:
        raise RuntimeError("APK v2 signature is required")
    return int(m.group(2))


def inspect_installed_apk(serial):
    """Read the emulator's installed base APK, not user data, and verify its signer."""
    paths = adb("-s", serial, "shell", "pm", "path", PACKAGE).splitlines()
    if len(paths) != 1 or not paths[0].startswith("package:/data/app/"):
        raise RuntimeError("Expected a single installed base APK on the emulator")
    remote = paths[0].removeprefix("package:")
    with tempfile.TemporaryDirectory(prefix="firetube-installed-apk-") as folder:
        local = Path(folder) / "installed.apk"
        adb("-s", serial, "pull", remote, str(local))
        return inspect_apk(local)



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
        old_version = installed_version(serial)
        if inspect_installed_apk(serial) != old_version:
            raise RuntimeError("Installed APK identity/version mismatch")
        before = snapshot(serial)
        # Exclusive creation protects earlier evidence; private permissions protect paths/hashes.
        import os
        fd = os.open(args.state, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(fd, "w") as out:
            json.dump({"package": PACKAGE, "serial": serial, "versionCode": old_version, "files": before}, out, indent=2)
        print(f"BASELINE SAVED: {len(before)} offline-related files, including audio")
        return
    if args.apk is None:
        raise RuntimeError("--apk required for upgrade")
    original = json.loads(args.state.read_text())
    if original["package"] != PACKAGE or original.get("serial") != serial:
        raise RuntimeError("Wrong baseline package or emulator; refusing cross-device comparison")
    old_version = installed_version(serial)
    if old_version != original.get("versionCode"):
        raise RuntimeError("Installed version differs from recorded baseline")
    if inspect_installed_apk(serial) != old_version:
        raise RuntimeError("Installed APK identity/version mismatch")
    before = original["files"]
    current = snapshot(serial)
    if before != current:
        raise RuntimeError("Offline files already changed BEFORE upgrade; refusing to continue")
    version = inspect_apk(args.apk)
    if version <= old_version:
        raise RuntimeError(f"Not an upgrade: installed={old_version}, incoming={version}")
    print(f"Upgrading disposable emulator to versionCode={version} with pinned signing certificate")
    adb("-s", serial, "install", "-r", str(args.apk))
    after_version = installed_version(serial)
    if after_version != version:
        raise RuntimeError(f"Installed version does not match incoming APK: {after_version} != {version}")
    if inspect_installed_apk(serial) != version:
        raise RuntimeError("Updated APK identity/version mismatch")
    after = snapshot(serial)
    if after != before:
        added = sorted(set(after) - set(before))
        missing = sorted(set(before) - set(after))
        changed = sorted(p for p in set(after) & set(before) if after[p] != before[p])
        raise RuntimeError(f"FAIL: offline files changed: added={added}, missing={missing}, modified={changed}")
    print(f"FILE PRESERVATION PASS: {len(before)} files preserved byte-for-byte after in-place APK update")
    print("NOTE: Manually confirm offline playback in airplane mode on the emulator.")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, OSError, ValueError, KeyError, subprocess.TimeoutExpired) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        sys.exit(1)

