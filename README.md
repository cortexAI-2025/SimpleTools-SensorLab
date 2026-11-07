# SimpleTools SensorLab

> **SimpleTools SensorLab — by AIWORKPAY.** Turn your Android phone into a pocket measurement lab using its real hardware sensors.

No account. No ads. No server. No telemetry. Works fully offline. Measurements stay on the device; *you* decide whether to export or share them.

| | |
|---|---|
| Package | `com.simpletools.sensorlab` |
| Version | 1.3.0 (versionCode 3) |
| Min / target SDK | Android 10 (API 29) / API 36 |
| Languages | Français (default), English |
| UI | Jetpack Compose + Material 3 (light, dark, system) |

## Free and PRO

| Free (always) | PRO — one-time purchase, for life, sold through Google Play |
|---|---|
| Detection of the sensors really present; absent ones are reported as absent | Everything in Free, plus: |
| Live values of every available sensor, including proximity | Timestamped session recording (start, pause, resume, finish) |
| Real-time charts, observed rate, Android accuracy when reported | Local history, zoomable charts |
| Technical details: vendor, resolution, power, range | Min / max / mean / RMS and effective rate per session |
| Language, theme, units, settings, delete-all | Comparison of several compatible sessions (up to 4) |
| | CSV, JSON and PDF export |
| | Local backup and restore (one JSON file, chosen by you) |

The product id is `simpletools_sensorlab_pro_lifetime`. The price shown is the one Google Play returns; it is not hard-coded in the app.

Sessions recorded before PRO existed are kept on the phone: they are shown as "PRO history" until PRO is unlocked. Nothing is deleted.

## Features

- **Dashboard**: real sensor detection through `SensorManager`, phone status, one card per module.
- **Modules**: accelerometer (with gravity), linear acceleration, gyroscope, magnetometer, light, proximity, barometer, ambient temperature, inclinometer / digital level, vibration meter (indicative).
- **Live charts** (custom Canvas): time axis, units, X/Y/Z curves, sliding window, min/max, gaps drawn as gaps, bounded memory.
- **Sessions** stored in Room with batched writes and an explicit storage policy.
- **Export**: CSV and JSON (streamed), one-page PDF report; save through the Storage Access Framework or share through the Android Sharesheet. No storage permission.

No simulated measurement is ever displayed: simulated sensors exist only in the unit-test source set.

## Build

```bash
# JDK 17+ and Android SDK (platform 36, build-tools 36.0.0)
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
./gradlew :app:assembleRelease :app:bundleRelease   # unsigned outputs
```

See [docs/BUILD.md](docs/BUILD.md). CI publishes:
- **`SimpleTools-SensorLab-Android-APK`**: debug APK and its SHA-256 (installable for testing);
- **`SimpleTools-SensorLab-Android-AAB-unsigned`**: release AAB and release APK, unsigned, with SHA-256.

## Documentation

| Topic | Document |
|---|---|
| Build, CI, artefacts | [docs/BUILD.md](docs/BUILD.md) |
| Architecture, storage, backup, billing design | [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| Google Play product and licence testing | [docs/PRO_BILLING.md](docs/PRO_BILLING.md) |
| Sensors, maths, limits | [docs/SENSORS.md](docs/SENSORS.md) |
| Privacy and permissions | [docs/PRIVACY.md](docs/PRIVACY.md) |
| Acceptance procedure on Samsung Galaxy A14 | [docs/GALAXY_A14_TEST_GUIDE.md](docs/GALAXY_A14_TEST_GUIDE.md) |

## Accuracy notice

Measurements come from the phone's consumer-grade sensors, are indicative, and are not calibrated or certified. They do not replace a qualified instrument.
