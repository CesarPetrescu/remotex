#!/usr/bin/env bash
# Build (and optionally install) the Remotex Android debug APK with the
# correct relay URL baked in. Without this wrapper the APK defaults to
# `http://10.0.2.2:8080` (the Android-emulator alias for the host's
# loopback) which is wrong for any real device on the LAN.
#
# Usage:
#   ./build.sh                           # detect LAN IP + relay port, build only
#   ./build.sh install                   # also `adb install -r` to the connected device
#   ./build.sh install 192.168.10.50     # override the auto-detected IP
#   RELAY_URL=https://relay.example.com ./build.sh   # full override
#
# IP auto-detection picks the first non-loopback, non-docker IPv4 address.
# Port comes from deploy/.env (RELAY_HOST_PORT) or defaults to 8080.
#
# Run from android/.
set -euo pipefail

cd "$(dirname "$0")"

action="${1:-build}"
explicit_ip="${2:-}"

if [[ -n "${RELAY_URL:-}" ]]; then
  relay_url="$RELAY_URL"
else
  if [[ -n "$explicit_ip" ]]; then
    ip="$explicit_ip"
  else
    ip="$(ip -4 addr show scope global 2>/dev/null \
            | awk '/inet / {print $2}' \
            | cut -d/ -f1 \
            | grep -vE '^(127\.|172\.(1[6-9]|2[0-9]|3[01])\.|100\.)' \
            | head -1)"
    if [[ -z "$ip" ]]; then
      echo "could not auto-detect LAN IP; pass it explicitly:"
      echo "  ./build.sh install 192.168.x.y"
      exit 1
    fi
  fi
  port="$(awk -F= '/^RELAY_HOST_PORT=/ {print $2}' ../deploy/.env 2>/dev/null | tr -d '\r' || true)"
  port="${port:-8080}"
  relay_url="http://${ip}:${port}"
fi

echo "→ building with RELAY_URL=$relay_url"
./gradlew :app:assembleDebug "-PrelayUrl=$relay_url"

apk="app/build/outputs/apk/debug/app-debug.apk"
if [[ ! -f "$apk" ]]; then
  echo "build finished but $apk not found"
  exit 1
fi

if [[ "$action" == "install" ]]; then
  echo "→ adb install -r $apk"
  adb install -r "$apk"
fi

echo "✓ done. APK: $(realpath "$apk")"
