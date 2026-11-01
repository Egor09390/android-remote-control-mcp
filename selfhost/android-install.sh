#!/usr/bin/env bash
# Runs over verified SSH after an Android SFTP upload.
# Destructive repair is restricted to the marked android-mcp private directory.
set -euo pipefail
umask 077
export DEBIAN_FRONTEND=noninteractive
die() { echo "ERROR: $*" >&2; exit 1; }
STAGE="$(cd "$(dirname "$0")" && pwd)"
TARGET="$HOME/.local/share/android-mcp-own-vps"
case "$STAGE" in
 "$HOME"/.android-mcp-staging-*) ;;
 *) die "Unsafe staging location" ;;
esac
trap 'rm -rf -- "$STAGE"' EXIT
FILES=(main.go adb_bridge.go go.mod go.sum Dockerfile docker-compose.yml Caddyfile .env android-install.sh)
[[ -n "$HOME" && "$HOME" != "/" ]] || die "Unsafe home directory"
[[ ! -L "$TARGET" ]] || die "Managed target must not be a symlink"
[[ -f "$STAGE/.manifest" ]] || die "Missing upload checksums"
(cd "$STAGE" && sha256sum -c .manifest --status) || die "SFTP files failed SHA-256 check"
grep -Eq '^MCP_DOMAIN=[A-Za-z0-9][A-Za-z0-9.-]*[A-Za-z0-9]$' "$STAGE/.env" || die "Invalid domain"
run_root() {
 if [[ "$(id -u)" -eq 0 ]]; then "$@"
 else sudo -n "$@" || die "Root or passwordless sudo required to install OS dependencies"
 fi
}
if ! command -v docker >/dev/null || ! docker compose version >/dev/null 2>&1; then
 command -v apt-get >/dev/null || die "Docker Engine and Docker Compose v2 must be installed"
 echo "Installing missing Docker components..."
 run_root apt-get update -qq
 run_root apt-get install -y -qq docker.io docker-compose-v2 || die "Docker Compose v2 unavailable from OS repository"
fi
docker info >/dev/null 2>&1 || die "SSH account needs Docker daemon access"
if [[ -e "$TARGET" && ! -f "$TARGET/.android-mcp-managed" ]]; then
 die "Target exists without our ownership marker; refusing to delete"
fi
MATCH=1
if [[ ! -d "$TARGET" ]]; then
 MATCH=0
else
 [[ "$(cat "$TARGET/.android-mcp-managed")" == "own-vps-v1" ]] || MATCH=0
 cmp -s "$STAGE/.manifest" "$TARGET/.manifest" || MATCH=0
 for file in "${FILES[@]}"; do
   [[ -L "$TARGET/$file" ]] && MATCH=0
   cmp -s "$STAGE/$file" "$TARGET/$file" || MATCH=0
 done
 while IFS= read -r found; do
   filename="$(basename "$found")"
   case "$filename" in
    main.go|adb_bridge.go|go.mod|go.sum|Dockerfile|docker-compose.yml|Caddyfile|.env|android-install.sh|.android-mcp-managed|.manifest) ;;
    *) MATCH=0 ;;
   esac
 done < <(find "$TARGET" -type f)
fi
cd "$HOME"
if [[ "$MATCH" -eq 0 ]]; then
 echo "AUDIT_CHANGED: replacing only managed android-mcp components..."
 if [[ -d "$TARGET" ]]; then
   (cd "$TARGET" && docker compose -p android-mcp-personal down --remove-orphans) || true
   rm -rf -- "$TARGET"
 fi
 mkdir -p "$TARGET"
 for file in "${FILES[@]}"; do cp -- "$STAGE/$file" "$TARGET/$file"; done
 cp -- "$STAGE/.manifest" "$TARGET/.manifest"
 printf 'own-vps-v1\n' > "$TARGET/.android-mcp-managed"
 chmod 600 "$TARGET/.env"
 (cd "$TARGET" && sha256sum -c .manifest --status) || die "Post-install integrity check failed"
 (cd "$TARGET" && docker compose -p android-mcp-personal build --no-cache --pull)
fi
cd "$TARGET"
docker compose -p android-mcp-personal up -d
for service in relay caddy; do
 ID="$(docker compose -p android-mcp-personal ps -q "$service")"
 [[ -n "$ID" ]] || die "Service $service was not created"
 [[ "$(docker inspect -f '{{.State.Running}}' "$ID")" == "true" ]] || die "Service $service not running"
done
echo "AUDIT_OK: VPS files verified and managed tunnel services running."
