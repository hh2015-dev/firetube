# Counters update signing

This branch targets only `com.palmerintech.firetube.drivefavoritescounterspreview`.
The installed baseline is versionCode 215. The next reviewed update is 217 (216 was consumed by build verification).
The approved certificate SHA-1 is `A4:1B:36:91:38:EB:A1:D5:0E:23:BF:11:09:55:BD:38:6B:EB:2A:68`.
Both SHA-1 and SHA-256 are pinned in signing.py. Do not create a replacement key.

## Local signed Gradle builds

Provide COUNTERS_KEYSTORE (absolute path), COUNTERS_STORE_PASSWORD,
COUNTERS_KEY_ALIAS, COUNTERS_KEY_PASSWORD through a private environment/session.
Never put values on the command line, in source files, in logs, or in build artifacts.
Run `python3 tools/counters-signing/signing.py preflight` before starting CI or Gradle.
Ordinary Gradle APK builds depend on this check and use the explicit counters
signing config for both debug and release; there is no debug signing fallback.
Python 3, Java/keytool, and Android build prerequisites are required.

## Remote compiler, local signer

Actions has NO signing secrets. It compiles an unsigned APK only, rejects signed
output, tests it, encrypts it to the existing private delivery recipient, and
uploads ciphertext with public verification metadata and Android's apksigner.jar.
`-PcountersUnsignedCompilation=true` is an explicitly separate CI compilation
stage, not a signed update build; it requires GITHUB_ACTIONS=true. The default
signed build fails when the approved key is absent. CI alone cannot issue updates.
Do not distribute or install the unsigned intermediate.

1. Locally run preflight with the approved private key before initiating a build.
2. Increase config/counters-version.json: previousVersionCode = the last allocated build
   code, versionCode > previousVersionCode; update VERSION_CODE and the expected
   badging check in favorites-counters.yml. Actions compares against preceding
   source history as well as the installed baseline. Reruns of the same source are
   allowed; independently distributed updates must always use a higher code.
3. Download the artifact for the exact reviewed commit. Check the artifact hash
   against GitHub metadata; decrypt the APK using the retained private transport
   key and AES-GCM authentication. Check plaintext SHA-256 against apk-sha256.txt.
   Keep the transport private key outside GitHub too. The envelope format is
   documented by tools/private-apk/encrypt_apk.py.
4. Run `python3 tools/counters-signing/signing.py sign UNSIGNED.apk apksigner.jar UPDATE.apk`.
   It fails on missing/wrong signing material, wrong identity, an already signed
   input, reused output filename, signing failure, or final certificate mismatch.
   Passwords go to apksigner via environment references. Both certificate hashes,
   package, versionCode, ZIP CRC, and APK v2 signature are verified. A failed result
   is removed. The adjacent .verification.json contains only public verification data.
5. Privately deliver the verified signed APK and verification report. No public
   Releases; release.yml is disabled on this branch. No raw APK upload in Actions.

Preserve the original keystore and transport private key in private durable backup
storage. A transient working directory alone is not a durable key backup. Never
rotate the signing identity; losing its private key prevents normal future updates.

Signing compatibility does not prove application migration correctness. This
change alters build configuration and the app label, not database schemas, data
migrations, Favorites, downloads, Drive authorization, or OAuth configuration.
No uninstall, clear-data operation, or device install is performed by these scripts.
