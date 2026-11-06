# Build, CI and APK

## Requirements

- JDK 17 or newer (CI uses Temurin 17; JDK 21 works locally).
- Android SDK with `platforms;android-36` and `build-tools;36.0.0`.
- Gradle comes from the wrapper (8.14.3).

## Toolchain (stable only)

| Component | Version | Why |
|---|---|---|
| Gradle | 8.14.3 | wrapper |
| Android Gradle Plugin | 8.13.2 | last 8.x; AGP 9.x is avoided in V1 (KSP/Room support still moving) |
| Kotlin | 2.3.21 | stable, Compose compiler plugin of the same version |
| KSP | 2.3.13 | Room compiler |
| Compose BOM | 2025.12.01 | newer AndroidX requires AGP 9.1+ and compileSdk 37 |
| Room / DataStore / Navigation / Lifecycle | 2.8.3 / 1.1.7 / 2.9.6 / 2.9.4 | pinned in `gradle/libs.versions.toml` |

Upgrading to AGP 9 + latest AndroidX is a tracked follow-up, not a V1 requirement.

## Commands

```bash
./gradlew :app:compileDebugKotlin          # compile
./gradlew :app:testDebugUnitTest           # unit + Robolectric tests
./gradlew :app:lintDebug                   # Android lint (errors fail the build)
./gradlew :app:assembleDebug               # APK in app/build/outputs/apk/debug/
./scripts/check-hygiene.sh                 # privacy guard (no permission/SDK/secret)
./scripts/check-apk-permissions.sh app/build/outputs/apk/debug/app-debug.apk
```

Optional local environment variables (CI does not need them):

- `ROBOLECTRIC_DEP_DIR`: directory containing a pre-downloaded `android-all-instrumented-15-robolectric-13954326-i7.jar` (offline Robolectric).
- `SENSORLAB_REPORT_DIR`: the PDF tests write a PNG preview of each rendered report there.

## GitHub Actions

`.github/workflows/android.yml`

- Triggers: `pull_request`, `push` on `main`, `workflow_dispatch`.
- Job **validate**: hygiene script, compile, unit tests, lint. Fails if any fails.
- Job **build-apk** (needs validate): `assembleDebug`, verifies the APK exists and is non-empty, audits its permissions (must be none), computes SHA-256, uploads artifact **`SimpleTools-SensorLab-Android-APK`** containing `SimpleTools-SensorLab-debug.apk` and `.sha256`. `if-no-files-found: error`.

### Download the APK

GitHub → *Actions* → select the run → *Artifacts* → `SimpleTools-SensorLab-Android-APK`. Verify with `sha256sum -c SimpleTools-SensorLab-debug.apk.sha256`.

### Install on the phone

Enable *install unknown apps* for your browser/file manager, copy the APK to the phone, open it. The debug APK is signed with the standard Android debug key: it is for testing, not for store distribution.
