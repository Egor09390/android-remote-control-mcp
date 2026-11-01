#!/usr/bin/env bash
# Run: sudo bash selfhost/setup-vps.sh mcp.example.com
set -euo pipefail
if [ "$#" -ne 1 ]; then
 echo "Usage: sudo bash selfhost/setup-vps.sh mcp.example.com" >&2
 exit 2
fi
DOMAIN="$1"
if [[ ! "$DOMAIN" =~ ^[a-zA-Z0-9][a-zA-Z0-9.-]*[a-zA-Z0-9]$ ]] || [[ "$DOMAIN" != *.* ]]; then
 echo "Invalid public domain" >&2
 exit 2
fi
command -v docker >/dev/null || { echo "Docker is missing"; exit 1; }
docker compose version >/dev/null || { echo "Docker Compose v2 is missing"; exit 1; }
command -v openssl >/dev/null || { echo "OpenSSL is missing"; exit 1; }
cd "$(dirname "$0")"
if [ -f .env ]; then
 echo ".env exists; preserving tunnel secret"
else
 umask 077
 TOKEN="$(openssl rand -hex 32)"
 printf 'MCP_DOMAIN=%s\nMCP_TUNNEL_TOKEN=%s\n' "$DOMAIN" "$TOKEN" > .env
 unset TOKEN
fi
chmod 600 .env
docker compose up -d --build
echo
echo "External MCP URL: https://$DOMAIN/mcp"
echo "Phone own VPS base URL: https://$DOMAIN"
echo "Secret token for phone (do not share):"
sed -n 's/^MCP_TUNNEL_TOKEN=//p' .env
echo "Logs: cd $(pwd) && sudo docker compose logs -f --tail=80"
