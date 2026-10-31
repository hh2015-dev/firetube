# Private APK delivery — no Play Store, no billing, no public artifacts

## Why not attach an APK to Actions on this repository?
The existing repository `hh2015-dev/firetube` is **public**. Anyone with repository
read access can access workflow artifacts, so attaching an unencrypted APK would
contradict the private-delivery requirement. The previous successful CI runs
intentionally compiled and checked the APK **without uploading it**.

## Browser-only delivery via your own GitHub Codespace
This alternative does not use ChatGPT Work, Android Studio or local compilation.
A Codespace is a remote private development workspace, accessible in the browser
to the owning GitHub account. You download the file directly to your computer
(or phone) when the build completes.

1. Before doing anything, check GitHub **Settings > Billing and licensing**:
   verify that no payment method is attached, that use remains within the included
   Codespaces allowance, and do not enable a paid spending limit or billing.
   GitHub Free individual quotas are currently **120 core-hours** and
   **15 GB-month** storage. These can change. With no payment method, GitHub
   blocks use when the free allocation is exhausted rather than charging you.
2. Open the `dev/private-apk-delivery` branch, select **Code > Codespaces >
   Create codespace on dev/private-apk-delivery**. Use the standard **2-core**
   instance. Do not select any paid add-on.
3. In the browser's VS Code terminal, run exactly:
   
   ```bash
   bash tools/private-apk/build-in-codespace.sh
   ```
   
   The script downloads Android's official command-line tools only when needed,
   verifies the SHA-256 value published by Google for version
   `commandlinetools-linux-15859902_latest.zip`, requests your interactive
   acceptance of Google's free SDK license, installs the project's Android 37
   platform/build tools, then runs unit tests, lint, and `assembleDebug`.
   It does **not** install Android Studio, sign into Google Drive, create
   a GitHub Release, publish an APK, or upload files.
4. If it says `LOCAL APK READY`, use the Codespaces Explorer to open
   `app/build/outputs/apk/debug/`, **right-click `app-debug.apk` > Download**.
   Microsoft documents this download action for browser-based Codespaces.
5. Copy the downloaded APK to your Android phone using a USB cable or a private
   transfer method. In Android settings, allow installing APKs from the chosen
   files app temporarily if prompted. Install and test locally.
6. When finished, **Stop** (or delete) the Codespace to avoid consuming its
   free compute and storage quotas.

## Important signing and test limitations
- A fresh Android debug keystore is usually generated **in that Codespace**.
  A different keystore on a future Codespace cannot update the existing app
  in place. Preserve it securely outside the public Git repository (do not
  publish it), or design a permanent private signing workflow before updating
  an app containing valuable user data.
- A debug build is for personal testing, not a Play Store release. If an APK
  using the same `applicationId` and a different signing certificate is already
  installed, Android will reject the update; do not uninstall without a backup.
- Google Drive via Android SAF requires Drive to appear in the system picker.
  Real Drive streaming/seek/background playback is **not yet verified**.
- SoundCloud's official widget is separate from the Media3 player; background
  playback, screen-off and queue integration are **not verified**.
- The SoundCloud and SAF candidate branch must first pass the matching GitHub
  Actions workflow. Codespaces script itself has not been executed or validated
  in an actual Codespace through this chat.

## Independent references
- Android SDK official download and checksum:
  https://developer.android.com/studio
- GitHub Codespaces quota:
  https://docs.github.com/en/billing/concepts/product-billing/github-codespaces
- GitHub workflow artifact access:
  https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts
- VS Code browser Codespaces file download:
  https://code.visualstudio.com/docs/remote/codespaces
