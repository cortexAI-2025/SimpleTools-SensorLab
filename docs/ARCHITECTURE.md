# Architecture

Single Gradle module (`:app`) organised by package, to stay simple and maintainable.

```
com.simpletools.sensorlab
├── AppContainer, MainActivity, SensorLabApplication     manual DI, entry point
├── core/sensors      SensorKind, SensorInfo, SensorSource (interface), AndroidSensorSource (SensorManager)
├── core/measurement  stats, filters, orientation maths, sampling-rate estimator, SessionRecorder (state machine)
├── core/database     Room: SessionEntity, SampleEntity, DAO, SessionRepository (implements SessionStore)
├── core/billing      BillingGateway (Play Billing port), PlayBillingGateway, LicenseManager, licence cache
├── core/backup       BackupService: whole-device JSON backup and restore
├── core/export       CsvExporter (streamed), PdfReport (PdfDocument), ExportService (SAF + FileProvider share)
├── core/charts       LineChart (Canvas), NiceScale
├── core/settings     AppSettings + DataStore repository
├── feature/dashboard | sensor | sessions | comparison | settings     Compose screens + ViewModels
└── ui                theme, navigation, shared components, icons
```

MVVM + repository, Kotlin coroutines, `StateFlow`, Room, DataStore, Material 3.

## Data flow of a measurement

```
SensorManager ─ callbackFlow(DROP_OLDEST buffer) ─▶ SensorViewModel.handle(sample)
   KindTransform (raw | tilt | high-pass) ─▶ MultiStats, SampleWindow (bounded), rate estimator
                                         └▶ SessionRecorder ─ batches of 500 ─▶ Room (SessionStore)
UI ticker (15 fps) ─ snapshot of the window (≤ 600 points) ─▶ Compose LineChart
```

Acquisition rate and render rate are independent. The chart window is bounded by age and by point count; live stats are O(1) memory.

## Session storage and retention policy

- One `sessions` row + N `samples` rows (`ON DELETE CASCADE`).
- Sample = active time `tUs` (pauses removed), wall clock `wallMs`, `v0..v2`, headline scalar.
- Recording decimates to the requested rate (`minIntervalNs`), writes by batches of **500** samples.
- Hard cap **300 000 samples per session**; at the cap the recording stops cleanly and the session is saved (`StopReason.LIMIT_REACHED`).
- Storage failure (disk full…): recording enters `FAILED`, already-written data is kept, the session is flagged `INTERRUPTED`; no crash.
- A session left `RECORDING` by a crash/kill is flagged `INTERRUPTED` at next start; its written samples are kept.
- No automatic purge; the user deletes sessions or everything (Settings).
- Statistics: scalar min/max/mean/RMS stored at finish; per-channel statistics computed by SQL aggregates on demand.
- Stored values are always in the sensor's native unit; unit conversion is display-only.

## Recorder time model

Hardware event timestamps are used (not wall time). While paused samples are dropped; on resume the silent gap (minus one nominal period) is removed from the active timeline so curves stay continuous, while `wallMs` keeps real time. Out-of-order / duplicate timestamps are rejected.

## Power

- Sensors start only in the measurement screen (`onStart`) and the listener is unregistered on `onStop` (rotation excepted).
- Dashboard performs only an inventory query; no sensor runs there.
- Requested rate is configurable (5 / 25 / 50 / 100 Hz, never above 100 Hz so no `HIGH_SAMPLING_RATE_SENSORS` permission is needed).
- Chart redraw capped at 15 fps; no background service, no WorkManager.
- V1 records in the foreground only: leaving the screen pauses the recording. Lock-screen recording would need a separate foreground service (Android 14+ requires a declared type and a notification) — out of V1 scope.

## Future extensions (out of V1, not built)

The `SensorSource` and `SessionStore` ports, the stored `method` identifier and the per-session settings summary leave room for: Aura (SimpleTools Pro AI assistant) analysis of sessions, indicative anomaly detection, external Bluetooth sensors (a new `SensorSource`), richer professional reports, SimpleTools Area / NFC Reader integrations and unified FieldKit reports. None of these exist in V1 and no network code is present.


## V1.3 additions

- **Licence** (`core/billing`): see [PRO_BILLING.md](PRO_BILLING.md). Entitlement is read through `LicenseManager.state`; screens and ViewModels check it, and `SensorViewModel.startRecording` checks it again. The licence lives in its own DataStore file, separate from settings.
- **Backup** (`core/backup`, `core/export/SessionJson`): one JSON file `format = simpletools-sensorlab/backup`, `version = 1`, streamed with `JsonWriter` / `JsonReader`. Restore imports each session in its own transaction; sessions whose id already exists are skipped, so a second restore changes nothing. A session still RECORDING in the file comes back INTERRUPTED. A newer version or another format is refused before anything is written.
- **Single-session JSON export**: same session encoding, one session per file.
- **Zoom** (`core/charts/ZoomWindow`): pure maths (zoom around an anchor, pan, clamp to the session, minimum span). The visible window is re-queried from Room (`samplesInRange`), decimated in SQL, so zooming shows real detail, not a stretched overview.
- **Comparison of up to 4 sessions** (reference included): all must match the reference's sensor, unit and method.
- **Schema**: unchanged, still version 1. No migration was needed; existing sessions are read as they are.
- **Recorder**: non-finite readings are dropped before storage (SQLite stores NaN as NULL, which would break a NOT NULL column and stop the recording).
