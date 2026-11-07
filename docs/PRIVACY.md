# Privacy and permissions

- No account, no SensorLab server, no ads, no analytics SDK, no crash reporter, no telemetry.
- Measurements, sessions, notes and backups stay on the device. Backups are a file you create and keep wherever you choose.
- Export and sharing are always an explicit user action: Storage Access Framework (you pick the destination) or the Android Sharesheet via `FileProvider` (a temporary copy in the app cache; only the latest export is kept).
- *Settings → Delete all data* removes every session, sample and cached export.
- Cloud backup and device-to-device transfer are disabled (`allowBackup=false`, `data_extraction_rules.xml`).

## Purchase data

PRO is bought and restored through Google Play. The app stores only the result: a PRO flag, the purchase token and the date of the last check, in its own preferences file. The app does not send them anywhere; SensorLab has no server to send them to.

## Permissions in the built app

SensorLab's own manifest declares **no** permission. The merged manifest contains only what the libraries declare, and the release audit (`scripts/check-apk-permissions.sh`) accepts exactly these:

| Permission | Origin | Why | Shown to the user |
|---|---|---|---|
| `com.android.vending.BILLING` | Google Play Billing | Unlocks PRO through Google Play | No |
| `android.permission.INTERNET` | Play Billing library manifest | Declared by the library; SensorLab's code makes no network request | No prompt |
| `android.permission.ACCESS_NETWORK_STATE` | Play Billing library manifest | Same | No prompt |
| `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | AndroidX Core | Signature permission, internal | No |

**Open decision:** the `INTERNET` and `ACCESS_NETWORK_STATE` entries are kept because they come from the billing library. Removing them (`tools:node="remove"` in the manifest) is only safe after a purchase, restore and offline test on a real device with Google Play. Until then they are listed here, not hidden.

No runtime permission is requested: no location, camera, microphone, storage or body sensor permission.
