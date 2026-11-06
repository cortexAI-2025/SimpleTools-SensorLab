#!/usr/bin/env bash
# Fails the build if the repository drifts from the privacy promises of SensorLab.
set -euo pipefail
cd "$(dirname "$0")/.."
fail=0

# 1. No permission may be declared in the app manifest.
if grep -nE '<uses-permission' app/src/main/AndroidManifest.xml; then
  echo "::error::The manifest must not declare any permission."; fail=1
fi

# 2. No analytics / ads / crash-reporting / network SDK (build files and Kotlin imports).
SDKS='firebase|crashlytics|admob|play-services|appsflyer|mixpanel|com\.amplitude|sentry|okhttp|retrofit|ktor|com\.facebook|segment\.analytics'
if grep -niE "$SDKS" app/build.gradle.kts gradle/libs.versions.toml; then
  echo "::error::Forbidden SDK dependency found."; fail=1
fi
if grep -rnE "^import +(${SDKS}|java\.net\.(Http|URL|Socket)|javax\.net)" app/src/main --include='*.kt'; then
  echo "::error::Forbidden SDK or network usage found."; fail=1
fi

# 3. No simulated sensor in production code (classes only; comments may mention the rule).
if grep -rnE '(class|object) +(Simulated|Fake|Mock)[A-Za-z]*' app/src/main --include='*.kt'; then
  echo "::error::Simulated sensors must live in app/src/test only."; fail=1
fi

# 4. Obvious secrets.
if grep -rnIE '(AKIA[0-9A-Z]{16}|-----BEGIN (RSA |EC )?PRIVATE KEY|ghp_[A-Za-z0-9]{30,}|api[_-]?key\s*=\s*"[^"]{8,})' \
     --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle --exclude=check-hygiene.sh .; then
  echo "::error::Possible secret committed."; fail=1
fi

# 5. No keystore committed.
if git ls-files | grep -E '\.(jks|keystore|p12|pem)$'; then
  echo "::error::Keystore files must not be committed."; fail=1
fi

[ "$fail" -eq 0 ] && echo "Hygiene checks passed."
exit "$fail"
