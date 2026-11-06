# Privacy

- No account, no server, no ads, no analytics SDK, no crash reporter, no telemetry.
- **No permission** is declared in the manifest: no Internet, location (GPS), camera, microphone, or storage. Android sensors used here do not require runtime permission at ≤ 200 Hz.
- Data lives in the app-private database on the device. Cloud backup and device-to-device transfer are disabled (`allowBackup=false`, `data_extraction_rules.xml`).
- Export/sharing is always an explicit user action: Storage Access Framework (user picks the destination) or Android Sharesheet via `FileProvider` (temporary file in the app cache, only the latest export is kept).
- *Settings → Delete all data* removes every session, sample and temporary export.
- The app does not log measurements.
- CI enforces these promises: `scripts/check-hygiene.sh` (no permission, no analytics/network SDK, no simulated sensor in production code, no secret) and `scripts/check-apk-permissions.sh` (built APK requests no permission).
