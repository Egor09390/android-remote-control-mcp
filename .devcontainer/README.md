# Headless Android 17 in GitHub Codespaces

This is **Android 17 / API 37**, **NOT Samsung One UI 9**.

**Important limitation**: GitHub does not guarantee KVM access in Codespaces; Microsoft says Android emulation in containers is not supported. A functioning Ubuntu **GitHub Actions** runner does not prove a Codespace can run this emulator.

The default config is safe to create even if /dev/kvm is missing, and automatically installs the SDK and Android 17 into persistent storage under `/workspaces/.android17`. After creation it attempts to start the headless emulator. If KVM is missing, it keeps the installation but prints a clear error (no amount of container configuration can create a missing host /dev/kvm).

The alternative `.devcontainer/kvm/devcontainer.json` explicitly forwards `--device=/dev/kvm` and grants privileges. **It can fail to CREATE a Codespace at all if the host has no KVM**; it also gives the container elevated rights. Only choose this experimentally if the default Codespace has a usable host device but does not expose it to the devcontainer.

## What to do

Create a Codespace on the repository's `main` branch. Use **4 cores / 16 GB RAM** or larger, potentially billed by GitHub. It automatically downloads Android 17 (API 37.0 SDK packaging), and tries to start it.

In the Codespaces terminal:

```bash
bash .devcontainer/android17.sh diagnose
bash .devcontainer/android17.sh status
bash .devcontainer/android17.sh start
bash .devcontainer/android17.sh test
```

- `KVM_ACCESS=ok` is required to boot with hardware acceleration.
- `PASS: headless Android 17 booted and ADB is responsive` confirms actual success.
- `KVM_ACCESS=missing` means **this Codespace cannot use this emulator**, not that the Android installation failed.
- `bash .devcontainer/android17.sh stop` stops it.

A Codespace can be stopped at idle (default 30 minutes); its workspace survives stops but is deleted after the applicable retention period. **This is not 24/7 hosting.** This configuration avoids publishing the ADB port and does not automatically connect ChatGPT to Android; installing and securing the Android MCP inside the guest is a separate step.

The automated GitHub Actions test (`.github/workflows/codespaces-android17-check.yml`) exercises this SAME script on a Linux runner with KVM and validates the config. It **cannot verify the KVM device in a real Codespace**.
