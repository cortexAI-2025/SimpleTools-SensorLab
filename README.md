# SimpleTools SensorLab

> **SimpleTools SensorLab — by AIWORKPAY.** Turn your Android phone into a pocket measurement lab using its real hardware sensors.

Free. No ads. No subscription. No account. No server. No telemetry. Works fully offline.
Measurements stay on the device; *you* decide whether to export or share them.

| | |
|---|---|
| Package | `com.simpletools.sensorlab` |
| Version | 1.0.0 (versionCode 1) |
| Min / target SDK | Android 10 (API 29) / API 36 |
| Languages | Français (default), English |
| UI | Jetpack Compose + Material 3 (light, dark, system) |
| Permissions | **none** (no Internet, location, camera, microphone or storage) |

## Features

- **Dashboard**: real sensor detection through `SensorManager`, phone status, last session, one card per module. Absent sensors are shown as absent — nothing is assumed from the phone model.
- **Modules**: accelerometer (with gravity), linear acceleration (gravity removed), gyroscope, magnetometer, light meter, barometer, ambient temperature, inclinometer / digital level, vibration meter (indicative).
- **Live charts** (custom Canvas): time axis, units, X/Y/Z curves, configurable sliding window, min/max, gaps drawn as gaps, bounded memory, rendering decoupled from acquisition.
- **Sessions** stored in Room: start, pause, resume, finish, rename, delete, notes. Batched writes, explicit storage policy.
- **Comparison** of two *compatible* sessions (same sensor, unit and computation method): table with absolute/percent differences and overlaid curves.
- **Export**: CSV (spreadsheet friendly, international or Excel-FR flavour) and one-page PDF report; save with the Storage Access Framework or share with the Android Sharesheet (FileProvider). No storage permission.
- **Settings**: theme, language, units, sampling rate, chart window, vibration filter, CSV format, storage, delete-all, privacy.

No simulated measurement is ever displayed: simulated sensors exist only in the unit-test source set.

## Build

```bash
# JDK 17+ and Android SDK (platform 36, build-tools 36.0.0)
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

See [docs/BUILD.md](docs/BUILD.md). The CI workflow `.github/workflows/android.yml` runs on pull requests, pushes to `main` and manual dispatch, and publishes the debug APK as the artifact **`SimpleTools-SensorLab-Android-APK`** (with its SHA-256).

## Documentation

| Topic | Document |
|---|---|
| Build, CI, APK download, versions | [docs/BUILD.md](docs/BUILD.md) |
| Architecture, storage policy, power | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Sensors, maths, limits | [docs/SENSORS.md](docs/SENSORS.md) |
| Acceptance procedure on Samsung Galaxy A14 | [docs/GALAXY_A14_TEST_GUIDE.md](docs/GALAXY_A14_TEST_GUIDE.md) |
| Privacy | [docs/PRIVACY.md](docs/PRIVACY.md) |

## Accuracy notice

Measurements come from the phone's consumer-grade sensors, are indicative, and are not calibrated or certified. They do not replace a qualified instrument (not a certified lux meter, vibration analyser, level or magnetometer).
