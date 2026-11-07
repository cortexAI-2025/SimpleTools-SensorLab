#!/usr/bin/env bash
# Usage: check-apk-permissions.sh path/to/app.apk
# SensorLab declares no permission itself. These entries are tolerated because they come from
# libraries, and each is listed here with its reason:
#  - com.android.vending.BILLING: Google Play Billing, needed to unlock PRO through Google Play.
#  - android.permission.INTERNET and ACCESS_NETWORK_STATE: declared by the Play Billing library
#    manifest. SensorLab's own code makes NO network request. Removing them is a decision to take
#    only after a real purchase test on a device (see docs/PRO_BILLING.md).
#  - DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION: signature permission added by AndroidX Core.
# Anything else fails the build.
set -euo pipefail
apk="${1:?apk path}"
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
aapt2=$(ls "$sdk"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -n 1)
[ -x "$aapt2" ] || { echo "aapt2 not found"; exit 2; }
out=$("$aapt2" dump permissions "$apk")
echo "$out"
perms=$(echo "$out" | grep -E '^uses-permission' \
  | grep -vE "name='(com\.android\.vending\.BILLING|android\.permission\.INTERNET|android\.permission\.ACCESS_NETWORK_STATE)'" \
  | grep -v 'DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION' || true)
if [ -n "$perms" ]; then
  echo "::error::Unexpected permissions requested:"; echo "$perms"; exit 1
fi
echo "Only library-declared permissions are present (billing, network state): no permission is requested by SensorLab code."
