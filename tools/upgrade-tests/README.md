# FireTube Counters — Drive offline upgrade regression

**Scope:** An emulator-only, non-destructive check of the **same-package, same-signer** update path. This is an additional regression guard, not evidence that the 215 → 217 incident has been reproduced or resolved.

## Prerequisites
- Disposable Android emulator with exactly one online `adb` device; physical handsets are rejected.
- Android SDK `adb`, `aapt`, and `apksigner` on PATH.
- An **older APK** with application ID `com.palmerintech.firetube.drivefavoritescounterspreview`, signed with SHA-1 `A4:1B:36:91:38:EB:A1:D5:0E:23:BF:11:09:55:BD:38:6B:EB:2A:68`. It must be **debuggable** so `run-as` can inspect app-private files.
- A **newer signed APK** with exactly that application ID and signing certificate and a higher versionCode.
- A dedicated test Google account and a disposable audio file in Drive. Do not use production user media or tokens in test logs.

## Static safety tests
Run `python3 -m unittest discover -s tools/upgrade-tests -p 'test_*.py' -v` before the emulator workflow. These tests mock Android SDK commands; they do **not** verify media survival across a real installation.

## Procedure

1. Install the older APK **only on the emulator**.
2. Open the app, connect the test account, select a test Drive audio file, choose **Download for offline**, and confirm it plays in airplane mode. Return online if needed for later steps. Do not clear app data.
3. On the host, run:

   `python3 tools/upgrade-tests/verify_drive_offline_upgrade.py baseline --state /tmp/fire-tube-drive-baseline.json`

4. With the newer signed APK available on the host, run:

   `python3 tools/upgrade-tests/verify_drive_offline_upgrade.py upgrade --state /tmp/fire-tube-drive-baseline.json --apk /absolute/path/to/newer.apk`

5. The script confirms that only one emulator is connected, the baseline is from that same emulator and installed version, the incoming versionCode increases, and the incoming application package and signature match; installs the update using `adb install -r`; then compares path, length, and SHA-256 of all test Drive offline files. It fails when the audio files were not present beforehand, the files differ, the signing identity differs, the package differs, or the emulator requirement is not satisfied.
6. After script PASS, open the app and **manually** confirm that the same test track still shows **Available offline** and plays in airplane mode. Record this result separately.

## What this does *not* prove

- The script does not test a complete Android UI playback sequence automatically.
- Matching file bytes alone does not prove that the app still resolves the proper account namespace or audio IDs; that is why the UI test is required.
- This test cannot be reported as passed until both old/new signed APKs and a configured emulator were actually used.
- The old package may be non-debuggable; `run-as` will fail safely. Do not root a phone, disable security, or extract private data to bypass this.
- No keystore, password, access token, binary APK, or personally owned music belongs in GitHub.
- Do not change `main`, install on a user's phone, clear data, or publish a release for this experiment.

## Incident under investigation
In manual tests, favorites survived update to versionCode 217, but a previously downloaded Drive track was no longer marked offline. Re-downloading worked and the new download survived app and phone restarts. The code used to name/store Drive audio did not differ between the compared source snapshots. Root cause is **not established**.
