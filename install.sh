#!/usr/bin/env bash
# Install (or update) WyzeCam RTSP on one or more Android / Google TV devices over the network.
#
#   ./install.sh 192.168.1.50 192.168.1.51:5555      # downloads the latest release APK
#   APK=./my-build.apk ./install.sh 192.168.1.50      # installs a local APK instead
#
# Each TV needs USB debugging (or Wireless debugging) turned on; accept the "Allow debugging?"
# prompt on the TV the first time. A device without a port uses 5555. Needs adb and curl.
set -euo pipefail

URL="https://github.com/crash101g/rtsp-wyzecam-apk/releases/latest/download/wyzecam-rtsp-tv.apk"

if [ $# -eq 0 ]; then
  echo "usage: $0 <tv-ip[:port]> [more TVs...]" >&2
  exit 1
fi
command -v adb >/dev/null || { echo "adb not found (install Android platform-tools)" >&2; exit 1; }

apk="${APK:-}"
if [ -z "$apk" ]; then
  apk="$(mktemp --suffix=.apk 2>/dev/null || mktemp)"
  trap 'rm -f "$apk"' EXIT
  echo "Downloading the latest APK…"
  curl -fL --progress-bar -o "$apk" "$URL"
fi

failed=0
for dev in "$@"; do
  [[ "$dev" == *:* ]] || dev="$dev:5555"
  echo "== $dev"
  adb connect "$dev" >/dev/null 2>&1 || true
  # Wait up to 60 s for the "Allow debugging?" prompt on the TV to be accepted.
  for _ in $(seq 1 30); do
    [ "$(adb -s "$dev" get-state 2>/dev/null)" = device ] && break
    sleep 2; adb connect "$dev" >/dev/null 2>&1 || true
  done
  if [ "$(adb -s "$dev" get-state 2>/dev/null)" != device ]; then
    echo "   not connected — is debugging on, and did you allow this computer on the TV?" >&2
    failed=1; continue
  fi
  model="$(adb -s "$dev" shell getprop ro.product.model | tr -d '\r')"
  if adb -s "$dev" install -r "$apk"; then echo "   installed on $model"; else echo "   install failed on $model" >&2; failed=1; fi
done
exit $failed
