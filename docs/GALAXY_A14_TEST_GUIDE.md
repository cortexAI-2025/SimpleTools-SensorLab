# Acceptance guide — Samsung Galaxy A14, Android 15

> Status: this procedure has **not** been executed on a physical Galaxy A14 by the author of the code (no device access). It is the recette to run. Missing sensors are **not** failures: the A14 typically has an accelerometer, a light/proximity sensor and a magnetometer; a gyroscope, barometer and ambient-temperature sensor may be absent — the app must then say so.

Record for each step: PASS / FAIL / N/A (sensor absent) and the observation.

1. **Install** the APK from the GitHub Actions artifact `SimpleTools-SensorLab-Android-APK` (check the SHA-256).
2. **Open** the app: no crash, no permission prompt.
3. **Sensor detection**: dashboard lists the 9 modules; available ones are marked *Disponible*, absent ones *Absent sur ce téléphone*. Compare with "Afficher tous les capteurs matériels".
4. **Accelerometer**: flat on a table → norm ≈ 9.8 m/s², Z ≈ +9.8. Shake → curves move; observed rate shown.
5. **Gyroscope** (if present): rotate the phone → rad/s change; still → ≈ 0. If absent → explicit message.
6. **Magnetometer**: ≈ 25–65 µT; move near a magnet or metal object → variation indicator changes (descriptive text only).
7. **Light meter** (if present): cover the sensor → value drops; torch/daylight → rises; category text changes.
8. **Inclinometer**: flat → ≈ 0°; lean one edge → bubble moves toward the high side; *Calibrer ici* on a reference surface → angles ≈ 0 there.
9. **Record a 30 s session** (e.g. accelerometer, 25 Hz): *Démarrer l'enregistrement*, move the phone, test *Pause* / *Reprendre*, wait ≥ 30 s active time.
10. **Stop** (*Terminer*): "Session enregistrée" appears.
11. **History**: the session is listed with duration and sample count (≈ 750 at 25 Hz).
12. **Export CSV**: *Enregistrer CSV* (choose Downloads) and *Partager CSV*; open in a spreadsheet; columns timestamp/elapsed/sensor/X/Y/Z/norm/unit; check the CSV format setting (international vs Excel FR).
13. **PDF**: *Enregistrer PDF* and *Partager PDF*; open it: title, AIWORKPAY, date, duration, sensor, unit, statistics, chart, notes, accuracy warning.
14. **Close and reopen** the app (swipe away from recents).
15. **Persistence**: the session, its chart and exports still work; statistics unchanged.
16. **Dark mode**: Réglages → Thème → Sombre (and system dark): readable contrast on all screens, charts included.
17. **No crash**: also test rotation during a measurement, back navigation, a second session + *Comparer*, *Supprimer toutes les données*, language switch FR/EN.

Extras: lock the screen during a recording → recording must pause (V1 is foreground only). Android Settings → Apps → SensorLab → Permissions must list none.
