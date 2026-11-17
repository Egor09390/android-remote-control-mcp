# Android 17 Headless on Ubuntu VPS (persistent)

**Not Samsung One UI 9.** This runs the stock Google APIs Android 17 (API 37) image, without GUI.

GitHub Actions cannot host a permanent Android. Its temporary runner is destroyed after a job finishes (maximum six hours for a GitHub-hosted job). This installer instead creates an **always-enabled systemd service on your own Ubuntu VPS**: it restarts Android after crashes and host reboots, and preserves installed APKs, settings and app data on the VPS disk.

Requirements: **Ubuntu 26.04 LTS**, x86_64, working `/dev/kvm` with KVM, sudo access, preferably **8+ GB RAM** and **20+ GB free disk**. It will not work on an ordinary GitHub Actions hosted runner as a permanent service. It has not been deployed on your VPS yet.

## One-time deployment on the VPS

```bash
git clone https://github.com/Egor09390/android-remote-control-mcp.git
cd android-remote-control-mcp
bash deploy/android17-vps/install.sh
```

The installer obtains Google SDK command-line tools, `android-37.0` Google APIs x86_64 system image, creates `android17` AVD, and enables the `android17-headless.service` systemd unit.

## Check it

```bash
sudo systemctl status android17-headless
sudo journalctl -u android17-headless -n 100 --no-pager
~/android-sdk/platform-tools/adb devices -l
~/android-sdk/platform-tools/adb shell getprop ro.build.version.sdk
```

The final command should return **37** after Android finishes booting. You can install your MCP Android APK with `~/android-sdk/platform-tools/adb install path/to/app.apk`, then configure its Accessibility permissions and endpoint. The APK is a separate tool, not part of this installer.

## Control / persistence

```bash
sudo systemctl restart android17-headless
sudo systemctl stop android17-headless
sudo systemctl start android17-headless
```

**Stored Android data:** `~/.android/avd/android17.avd/` (back it up separately). `-no-snapshot` disables Quick Boot snapshots, **not** Android's userdata persistence.

No public ADB port is opened. Reach the VPS by SSH; do not forward or publish the emulator/ADB port publicly without a secured tunnel. The service runs only while your VPS exists and is powered on; it is **not** protected from VPS deletion, storage loss, provider suspension, or quota exhaustion. Set up backups for important Android data.
