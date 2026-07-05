#!/usr/bin/env bash
# SonarSight laptop mirror — glasses POV + live SceneState in a browser.
#
# The phone runs a WebSocket server (SceneSocket, port 8080) that broadcasts the
# Ray-Ban POV frame + SceneState. This script bridges phone:8080 to
# localhost:8080 over the USB cable (adb forward — no Wi-Fi needed, immune to
# venue networks), serves the dashboard, and opens it pointed at the bridge.
#
# Usage:
#   scripts/laptop_mirror.sh            # USB cable (recommended for demos)
#   scripts/laptop_mirror.sh 10.0.0.42  # Wi-Fi: phone's IP, no cable needed
#
# Stop with Ctrl-C.

set -euo pipefail

ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
HTTP_PORT="${HTTP_PORT:-8000}"
DASHBOARD_DIR="$(cd "$(dirname "$0")/../live-dashboard" && pwd)"

if [[ $# -ge 1 ]]; then
    WS_HOST="$1"   # phone's Wi-Fi IP
    echo "Wi-Fi mode: connecting to ws://$WS_HOST:8080 (phone and laptop must share a network)"
else
    WS_HOST="localhost"
    "$ADB" get-state >/dev/null 2>&1 || { echo "No phone over USB. Plug it in (or pass the phone's Wi-Fi IP)."; exit 1; }
    "$ADB" forward tcp:8080 tcp:8080
    echo "USB mode: phone:8080 bridged to localhost:8080"
fi

URL="http://localhost:$HTTP_PORT/?ws=ws%3A%2F%2F$WS_HOST%3A8080"
echo "Dashboard: $URL"
( sleep 1; open "$URL" 2>/dev/null || xdg-open "$URL" 2>/dev/null || true ) &

cd "$DASHBOARD_DIR"
exec python3 -m http.server "$HTTP_PORT"
