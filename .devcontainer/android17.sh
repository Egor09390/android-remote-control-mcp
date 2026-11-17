#!/usr/bin/env bash
# Android 17 headless, ADB-based, persistent Codespaces workspace data.
set -Eeuo pipefail

STATE="${ANDROID_CODESPACE_HOME:-/workspaces/.android17}"
SDK="$STATE/sdk"
AVD="$STATE/avd"
AVD_NAME="codespace-android17"
LOG="$STATE/emulator.log"
export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"
export ANDROID_AVD_HOME="$AVD"
export PATH="$SDK/cmdline-tools/latest/bin:$SDK/platform-tools:$SDK/emulator:$PATH"
mkdir -p "$STATE" "$AVD"

has_kvm() {
  [[ -c /dev/kvm && -r /dev/kvm && -w /dev/kvm ]]
}

diag() {
  echo "Kernel: $(uname -srmo)"
  echo "User: $(id)"
  echo "RAM: $(free -h | awk '/Mem:/ { print $2 }')"
  if [[ -e /dev/kvm ]]; then
    ls -l /dev/kvm
    if has_kvm; then echo "KVM_ACCESS=ok"; else echo "KVM_ACCESS=denied"; fi
  else
    echo "KVM_ACCESS=missing (Codespaces does not guarantee KVM)"
  fi
}

install_sdk() {
  diag
  if [[ "$(uname -m)" != "x86_64" ]]; then
    echo "ERROR: this Android x86_64 image needs an x86_64 host." >&2
    exit 1
  fi
  if [[ ! -x "$SDK/cmdline-tools/latest/bin/sdkmanager" ]]; then
    echo "Installing headless Android SDK tools..."
    sudo apt-get update -qq
    sudo DEBIAN_FRONTEND=noninteractive apt-get install -y \
      curl unzip ca-certificates openjdk-21-jdk-headless \
      libstdc++6 libnss3 libx11-6 libxcb1 libxext6 libgl1
    local tmp
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT
    curl -fL --retry 3 \
      "https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip" \
      -o "$tmp/tools.zip"
    unzip -q "$tmp/tools.zip" -d "$tmp"
    mkdir -p "$SDK/cmdline-tools/latest"
    cp -a "$tmp/cmdline-tools/." "$SDK/cmdline-tools/latest/"
    rm -rf "$tmp"
    trap - EXIT
  fi
  local rc
  set +e
  yes | sdkmanager --licenses >/dev/null
  rc="${PIPESTATUS[1]}"
  set -e
  if (( rc != 0 )); then
    echo "ERROR: could not accept Android SDK licenses." >&2
    exit "$rc"
  fi
  sdkmanager --install --channel=0 \
    "platform-tools" "emulator" "platforms;android-37.0" \
    "system-images;android-37.0;google_apis;x86_64"
  if [[ ! -f "$AVD/$AVD_NAME.avd/config.ini" ]]; then
    printf 'no\n' | avdmanager create avd \
      -n "$AVD_NAME" -k "system-images;android-37.0;google_apis;x86_64"
  fi
  local config="$AVD/$AVD_NAME.avd/config.ini"
  sed -i '/^hw.ramSize=/d; /^hw.cpu.ncore=/d' "$config"
  printf 'hw.ramSize=4096\nhw.cpu.ncore=2\n' >> "$config"
  echo "SDK_READY=$SDK"
  echo "AVD_READY=$AVD_NAME"
  echo "Android userdata stored persistently under $AVD"
}

start() {
  diag
  if [[ ! -x "$SDK/emulator/emulator" ]]; then
    echo "SDK not installed yet. Run: bash .devcontainer/android17.sh install"
    exit 1
  fi
  if ! has_kvm; then
    echo "CANNOT_START: no usable /dev/kvm in THIS Codespace."
    echo "The installation is preserved. Codespaces virtualization cannot be enabled by a script."
    exit 2
  fi
  if pgrep -u "$(id -u)" -f "emulator.*-avd $AVD_NAME" >/dev/null; then
    echo "Emulator already running."
    return 0
  fi
  echo "Starting Android 17 without GUI, keeping its /data partition..."
  nohup "$SDK/emulator/emulator" \
    -avd "$AVD_NAME" -no-window -no-audio -gpu software \
    -no-boot-anim -no-snapshot -camera-back none -camera-front none \
    >"$LOG" 2>&1 </dev/null &
  echo "EMULATOR_PID=$!"
  echo "Log: $LOG"
}

test_device() {
  diag
  if ! has_kvm; then
    echo "FAIL: /dev/kvm unavailable. An Android emulator cannot run with KVM here." >&2
    return 2
  fi
  if [[ ! -x "$SDK/platform-tools/adb" ]]; then
    echo "FAIL: ADB not installed." >&2
    return 1
  fi
  local boot="" sdk="" i
  for ((i=1; i<=120; i++)); do
    boot="$("$SDK/platform-tools/adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)"
    if [[ "$boot" == 1 ]]; then break; fi
    sleep 2
  done
  "$SDK/platform-tools/adb" devices -l
  sdk="$("$SDK/platform-tools/adb" shell getprop ro.build.version.sdk 2>/dev/null | tr -d '\r' || true)"
  echo "BOOT_COMPLETE=$boot"
  echo "SDK_LEVEL=$sdk"
  if [[ "$boot" != 1 || "$sdk" != 37 ]]; then
    echo "FAIL: Android not ready or SDK is not 37." >&2
    if [[ -f "$LOG" ]]; then tail -n 80 "$LOG"; fi
    return 1
  fi
  echo "PASS: headless Android 17 booted and ADB is responsive."
}

case "${1:-status}" in
  install) install_sdk ;;
  start) start ;;
  test) test_device ;;
  diagnose) diag ;;
  status)
    diag
    if [[ -x "$SDK/platform-tools/adb" ]]; then "$SDK/platform-tools/adb" devices -l; fi
    if [[ -f "$LOG" ]]; then tail -n 15 "$LOG"; fi
    ;;
  stop)
    if [[ -x "$SDK/platform-tools/adb" ]]; then "$SDK/platform-tools/adb" emu kill || true; fi
    ;;
  *)
    echo "Usage: $0 {install|start|test|diagnose|status|stop}" >&2
    exit 2 ;;
esac
