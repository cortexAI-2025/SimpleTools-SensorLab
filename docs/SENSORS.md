# Sensors and measurement notes

All detection goes through `SensorManager.getDefaultSensor()` / `getSensorList()`. For each sensor the app reads name, vendor, Android type, resolution, maximum range, declared power, minimum delay (→ maximum rate) and reporting mode. A missing sensor is reported as absent; **nothing is derived from the phone model**.

| Module | Android source | Native unit | Notes |
|---|---|---|---|
| Accelerometer (with gravity) | `TYPE_ACCELEROMETER` | m/s² | norm ≈ 9.81 at rest |
| Linear acceleration | `TYPE_LINEAR_ACCELERATION` | m/s² | gravity removed by the system; separate module, never compared with the one above |
| Gyroscope | `TYPE_GYROSCOPE` | rad/s | optional °/s display. If absent: explicit state, no reconstruction from other sensors |
| Magnetometer | `TYPE_MAGNETIC_FIELD` | µT | Android accuracy shown; variation indicator is descriptive only (never "metal detected") |
| Light meter | `TYPE_LIGHT` | lx | indicative categories; not a calibrated lux meter. Many phones report on change only |
| Barometer | `TYPE_PRESSURE` | hPa | optional mmHg; no altitude (needs a reliable reference pressure) |
| Ambient temperature | `TYPE_AMBIENT_TEMPERATURE` | °C | °F shown too. Battery/CPU temperature is never used. Most phones lack this sensor |
| Inclinometer / level | `TYPE_ROTATION_VECTOR`, fallback `TYPE_ACCELEROMETER` | ° | pitch, roll, tilt; deprecated orientation sensors are not used |
| Vibration meter | `TYPE_ACCELEROMETER` + high-pass | m/s² | indicative; no frequency claimed |

## Inclinometer

Everything is derived from the unit "up" vector `u` in the device frame (third row of the rotation matrix from the rotation vector, or the normalised, low-passed gravity when no rotation-vector sensor exists; the fallback is labelled in the UI and stored in the session `method`).

- tilt = acos(u·ref) — angle to the vertical (or to the calibration reference),
- pitch = atan2(uy, √(ux²+uz²)), roll = atan2(ux, uz), minus the reference's values.

Calibration stores the current `u` as reference: angles become relative to *that surface*. It does not improve hardware accuracy.

## Vibration meter

Per-axis first-order high-pass filter, cutoff configurable (0.5–5 Hz, default 1 Hz):

```
RC = 1 / (2π·fc);  α = RC / (RC + dt);  y[i] = α · (y[i-1] + x[i] − x[i-1])
```

`dt` comes from sample timestamps. RMS is computed over a sliding window (1, 2 or 5 s) on the norm of the filtered vector. The phone's real sampling rate (typically ≤ 100 Hz here) bounds what can be observed (Nyquist); no vibration frequency is displayed. Not an industrial vibration analysis.

## Sampling

Requested periods: 5, 25, 50, 100 Hz (Android treats it as a hint). The *observed* rate is computed from hardware timestamps over the last 100 samples and shown live and stored per session. Rates above 200 Hz would require the `HIGH_SAMPLING_RATE_SENSORS` permission and are not offered.

## Comparison rules

Two sessions are comparable only if `sensor kind`, `unit` and `method` are identical (e.g. linear acceleration vs accelerometer, or rotation-vector tilt vs gravity-fallback tilt, are refused). Percent differences are omitted for quantities where they have no physical meaning (temperature in °C, tilt) or when the reference is ≈ 0. RMS is not shown for temperature, pressure and light.
