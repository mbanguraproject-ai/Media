#!/usr/bin/env bash
# Idempotent post-create setup: installs the Android SDK (platform 36,
# build-tools 36.0.0, platform-tools) and points the project at it.
# JDK 17 is provided by the base image. Safe to re-run.
set -e

ANDROID_HOME="$HOME/android-sdk"
CMDLINE_ZIP_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"

echo ">> Android SDK setup starting..."

if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo ">> Installing command-line tools..."
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  tmp="$(mktemp -d)"
  curl -sL -o "$tmp/cmdtools.zip" "$CMDLINE_ZIP_URL"
  unzip -q "$tmp/cmdtools.zip" -d "$ANDROID_HOME/cmdline-tools"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf "$tmp"
else
  echo ">> Command-line tools already present."
fi

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

echo ">> Accepting licenses..."
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true

echo ">> Installing platform-36, build-tools 36.0.0, platform-tools..."
"$SDKMANAGER" "platform-tools" "platforms;android-36" "build-tools;36.0.0" >/dev/null

# Point the project at the SDK (local.properties is gitignored).
echo "sdk.dir=$ANDROID_HOME" > /workspaces/Media/local.properties

echo ">> Android SDK ready at $ANDROID_HOME"
