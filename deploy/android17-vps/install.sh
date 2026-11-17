#!/usr/bin/env bash
# Persistent Android 17 headless VM for Ubuntu 26.04 x86_64 VPS with KVM.
set -Eeuo pipefail

if [[ "$(id -u)" -eq 0 ]]; then
  echo "Run as a regular sudo-enabled user, not root." >&2
  exit 1
fi
if [[ "$(uname -m)" != "x86_64" ]]; then
  echo "Requires x86_64 VPS." >&2
  exit 1
fi
if [[ ! -c /dev/kvm ]]; then
  echo "No /dev/kvm on this VPS. Ask host for nested virtualization/KVM." >&2
  exit 1
fi
if (( $(awk '/MemTotal:/ {print $2}' /proc/meminfo) < 7 * 1024 * 1024 )); then
  echo "Warning: at least 8 GB VPS RAM is recommended for a 4 GB Android 17 AVD." >&2
fi

SDK_ROOT="$HOME/android-sdk"
SDK_TOOLS_VERSION=16111833
echo "Installing Android SDK prerequisites..."
sudo apt-get update
sudo apt-get install -y \
  ca-certificates curl unzip openjdk-21-jdk-headless \
  libstdc++6 libnss3 libx11-6 libxcb1 libxext6 libgl1
sudo usermod -aG kvm "$(id -un)"

mkdir -p "$SDK_ROOT/cmdline-tools"
if [[ ! -x "$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" ]]; then
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' EXIT
  curl -fL --retry 3 \
    "https://dl.google.com/android/repository/commandlinetools-linux-${SDK_TOOLS_VERSION}_latest.zip" \
    -o "$tmp/tools.zip"
  unzip -q "$tmp/tools.zip" -d "$tmp"
  mkdir -p "$SDK_ROOT/cmdline-tools/latest"
  cp -a "$tmp/cmdline-tools/." "$SDK_ROOT/cmdline-tools/latest/"
fi

export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"
export PATH="$SDK_ROOT/cmdline-tools/latest/bin:$SDK_ROOT/platform-tools:$SDK_ROOT/emulator:$PATH"
echo "Accepting Android SDK licenses..."
set +e
yes | sdkmanager --licenses > /dev/null
sdk_rc="${PIPESTATUS[1]}"
set -e
if (( sdk_rc != 0 )); then
  echo "Android SDK license acceptance failed." >&2
  exit 1
fi

echo "Installing Android 17 (API 37; package name android-37.0)..."
sdkmanager --install --channel=0 \
  "platform-tools" \
  "emulator" \
  "platforms;android-37.0" \
  "system-images;android-37.0;google_apis;x86_64"

AVD_HOME="$HOME/.android/avd"
mkdir -p "$AVD_HOME"
if [[ ! -f "$AVD_HOME/android17.avd/config.ini" ]]; then
  printf 'no\n' | avdmanager create avd --force \
    --name android17 \
    --package "system-images;android-37.0;google_apis;x86_64"
fi

config="$AVD_HOME/android17.avd/config.ini"
sed -i '/^hw.ramSize=/d; /^hw.cpu.ncore=/d' "$config"
printf 'hw.ramSize=4096\nhw.cpu.ncore=2\n' >> "$config"

user_name="$(id -un)"
echo "Creating systemd service for ${user_name}..."
sudo tee /etc/systemd/system/android17-headless.service > /dev/null <<SERVICE
[Unit]
Description=Android 17 headless emulator with persistent AVD
After=network-online.target
Wants=network-online.target
ConditionPathExists=/dev/kvm
StartLimitIntervalSec=0

[Service]
Type=simple
User=${user_name}
SupplementaryGroups=kvm
Environment=HOME=${HOME}
Environment=ANDROID_HOME=${SDK_ROOT}
Environment=ANDROID_SDK_ROOT=${SDK_ROOT}
Environment=ANDROID_AVD_HOME=${AVD_HOME}
WorkingDirectory=${HOME}
ExecStart=${SDK_ROOT}/emulator/emulator -avd android17 -no-window -no-audio -gpu software -no-boot-anim -no-snapshot -camera-back none -camera-front none
Restart=always
RestartSec=15
TimeoutStopSec=60

[Install]
WantedBy=multi-user.target
SERVICE

sudo systemctl daemon-reload
sudo systemctl enable --now android17-headless.service
echo "Service enabled. Data persists in ${AVD_HOME}/android17.avd across service/VPS restarts."
echo "Status: sudo systemctl status android17-headless"
echo "Logs: sudo journalctl -u android17-headless -f"
echo "ADB: ${SDK_ROOT}/platform-tools/adb devices -l"
