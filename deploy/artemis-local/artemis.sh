#!/usr/bin/env bash
set -euo pipefail
umask 077

scripts="$(cd "$(dirname "$0")" && pwd)"
state="$HOME/.artemis-local"
upstream="$state/artemis"
revision=351ca8422f7b5b54e80a9c1ce03a222e02415b6b
socket="$state/adb.sock"
device_file="$state/device"
token_file="$state/token"

case "${1:-}" in
  install)
    apt-get update
    apt-get install -y adb ca-certificates ffmpeg git libgl1 libglib2.0-0t64 openssl python3-venv
    mkdir -p "$state"
    chmod 700 "$state"
    if [[ ! -d "$upstream/.git" ]]; then
      git init -q "$upstream"
    fi
    if [[ "$(git -C "$upstream" rev-parse HEAD 2>/dev/null || true)" != "$revision" ]]; then
      git -C "$upstream" fetch --depth=1 https://github.com/google/artemis.git "$revision"
      git -C "$upstream" checkout --detach FETCH_HEAD
    fi
    python3 -m venv "$state/bootstrap"
    "$state/bootstrap/bin/pip" install uv==0.13.9
    (cd "$upstream" && "$state/bootstrap/bin/uv" sync --locked --no-dev --no-install-project --no-python-downloads)
    if [[ ! -s "$token_file" ]]; then
      openssl rand -hex 32 > "$token_file"
    fi
    if [[ ! -e "$upstream/.env" ]]; then
      cp "$upstream/.env.example" "$upstream/.env"
    fi
    printf 'Configure a model API key in %s and pair this phone before starting.\n' "$upstream/.env"
    exit
    ;;
  token)
    cat "$token_file"
    exit
    ;;
  pair|start) ;;
  *)
    printf 'Usage: bash %s {install|pair PAIR_PORT DEVICE_PORT|start|token}\n' "$0" >&2
    exit 2
    ;;
esac

[[ -x "$upstream/.venv/bin/python" && -s "$token_file" ]] || { echo "Run install first." >&2; exit 1; }
export ARTEMIS_REAL_ADB
ARTEMIS_REAL_ADB="$(command -v adb)"
export ARTEMIS_LOCAL_ADB_SOCKET="$socket"
export ADB_SERVER_SOCKET="localfilesystem:$socket"
export ARTEMIS_ADB_PATH="$scripts/bin/adb" ADBUTILS_ADB_PATH="$scripts/bin/adb"
export PATH="$scripts/bin:$PATH"
export ADB_MDNS_AUTO_CONNECT=0
export ARTEMIS_STANDALONE=1 ARTEMIS_HELPER_AUTO_INSTALL=false PYTHONDONTWRITEBYTECODE=1
export PYTHONPATH="$scripts:$upstream"

validate_port() {
  [[ "$1" =~ ^[1-9][0-9]{0,4}$ ]] && (( "$1" <= 65535 ))
}

# Each cmd owns the private ADB server for its lifetime. The keys persist in the
# Ubuntu container so a successful pairing survives stopping the server.
exec 9>"$state/lock"
flock -n 9 || { echo "ARTEMIS is already running. Stop it before pairing again." >&2; exit 1; }
cleanup() {
  "$ARTEMIS_REAL_ADB" kill-server >/dev/null 2>&1 || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
"$ARTEMIS_REAL_ADB" start-server 9>&-

if [[ "$1" == pair ]]; then
  [[ $# == 3 ]] && validate_port "$2" && validate_port "$3" || {
    echo "Supply the pairing port and the main Wireless debugging port." >&2
    exit 2
  }
  "$ARTEMIS_REAL_ADB" pair "127.0.0.1:$2"
  candidate="127.0.0.1:$3"
  "$ARTEMIS_REAL_ADB" connect "$candidate"
  [[ "$("$ARTEMIS_REAL_ADB" -s "$candidate" get-state)" == device ]] || {
    echo "Android did not authorize the device. Check both ports and the pairing code." >&2
    exit 1
  }
  printf '%s\n' "$candidate" > "$device_file"
  exit
fi

[[ -s "$device_file" ]] || { echo "Pair this phone first." >&2; exit 1; }
export ADB_DEVICE_SERIAL
ADB_DEVICE_SERIAL="$(cat "$device_file")"
if [[ "$ADB_DEVICE_SERIAL" != 127.0.0.1:* ]] || ! validate_port "${ADB_DEVICE_SERIAL#127.0.0.1:}"; then
  echo "The saved device must be this phone. Pair it again." >&2
  exit 1
fi
"$ARTEMIS_REAL_ADB" connect "$ADB_DEVICE_SERIAL"
[[ "$("$ARTEMIS_REAL_ADB" -s "$ADB_DEVICE_SERIAL" get-state)" == device ]] || {
  echo "Wireless debugging changed or is off. Pair this phone again." >&2
  exit 1
}
cd "$upstream"
# Keep the shell in charge of the socket and forward termination to the bridge.
"$upstream/.venv/bin/python" "$scripts/bridge.py" "$token_file" &
bridge_pid=$!
trap 'kill -INT "$bridge_pid" 2>/dev/null || true; wait "$bridge_pid" || true; exit 130' INT
trap 'kill -TERM "$bridge_pid" 2>/dev/null || true; wait "$bridge_pid" || true; exit 143' TERM
wait "$bridge_pid"
