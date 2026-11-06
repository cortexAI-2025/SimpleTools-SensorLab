#!/usr/bin/env bash
# Usage: check-apk-permissions.sh path/to/app.apk
# SensorLab must request no permission. The only tolerated entry is the signature-level
# DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION that AndroidX Core adds to every app (not user-visible).
set -euo pipefail
apk="${1:?apk path}"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
aapt2=$(ls "$sdk"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -n 1)
[ -x "$aapt2" ] || { echo "aapt2 not found"; exit 2; }
out=$("$aapt2" dump permissions "$apk")
echo "$out"
perms=$(echo "$out" | grep -E '^uses-permission' | grep -v 'DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION' || true)
if [ -n "$perms" ]; then
  echo "::error::Unexpected permissions requested:"; echo "$perms"; exit 1
fi
echo "No user-facing permission requested."
