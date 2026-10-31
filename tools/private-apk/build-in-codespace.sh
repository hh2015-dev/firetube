#!/usr/bin/env bash
# Build a personal-use APK inside YOUR private GitHub Codespace.
# Does not create a Release, upload an artifact, publish a file, or activate billing.
# Run manually: bash tools/private-apk/build-in-codespace.sh
set -euo pipefail

cd "$(dirname "$0")/../.."

if ! command -v java >/dev/null 2>&1; then
  echo "Java is not installed in this Codespace. Select an image that contains JDK 21." >&2
  exit 1
fi
java -version

export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
mkdir -p "$ANDROID_HOME/cmdline-tools"

if [ -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "Using existing Android SDK command-line tools."
else
  echo "Downloading Android command-line tools from the official Google source."
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fL --retry 3 --connect-timeout 20 \
    'https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip' \
    -o "$tmp/android-cli.zip"
  echo '4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583  '"$tmp/android-cli.zip" | sha256sum -c -
  unzip -q "$tmp/android-cli.zip" -d "$tmp/extracted"
  mv "$tmp/extracted/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  echo "Android command-line tools verified and installed for this Codespace."
fi

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

echo ""
echo "Android SDK licenses are displayed for YOUR review. Accept only if you agree."
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" --licenses

echo "Installing only free Android SDK build components."
"$SDKMANAGER" --sdk_root="$ANDROID_HOME" --install \
  'platform-tools' 'platforms;android-37' 'build-tools;37.0.0'

echo "Running Android tests, lint and debug APK build (not publishing)."
bash ./gradlew :core:extractor:test testDebugUnitTest lintDebug assembleDebug \
  --no-daemon --max-workers=2 --stacktrace

apk='app/build/outputs/apk/debug/app-debug.apk'
test -s "$apk"
echo ""
echo "LOCAL APK READY (inside this private Codespace): $apk"
wc -c "$apk"
sha256sum "$apk"
echo "In Codespaces Explorer, right-click app-debug.apk and select Download."
echo "No GitHub Release or public artifact has been created."
