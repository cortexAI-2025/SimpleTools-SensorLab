# PRO: Google Play Billing setup and licence tests

## What the app does

- `PlayBillingGateway` wraps Google Play Billing Library 8.3.0 (`com.android.billingclient:billing-ktx`).
- `LicenseManager` is the single source of truth. Rules:
  - PRO is granted only when Google Play reports a **PURCHASED** state for `simpletools_sensorlab_pro_lifetime`.
  - The purchase is **acknowledged** if it is not already. A failed acknowledgement keeps PRO and is retried at the next check.
  - A **PENDING** purchase never grants PRO; the message says it will unlock once approved.
  - When Play answers and reports **no** purchase (refund, other Google account), the cached licence is revoked.
  - When Play **cannot answer** (offline, no Play Store), the cached licence stays in force.
  - Restore asks Play again and reports the outcome.
- The price is the one returned by Play (`formattedPrice`). Nothing is hard-coded.
- Debug builds have no switch to force PRO. Tests use a simulated Play (`FakeBillingGateway`), in the test source set only.

## Play Console setup (to do by the publisher)

1. Create the app in Google Play Console with package `com.simpletools.sensorlab`.
2. *Monetise → Products → In-app products*: create a **one-time product** with product id **`simpletools_sensorlab_pro_lifetime`** (must match `PRO_PRODUCT_ID` in `Billing.kt`), price **4,99 €** (or the local equivalent Play proposes), status **Active**.
3. *Setup → License testing*: add the Google accounts that will test purchases (they buy without being charged).
4. Upload the **signed** AAB to an internal testing track (the unsigned AAB from CI must be signed with the upload key first).
5. Install from the Play internal testing link on the Galaxy A14, not from a sideloaded APK: Play Billing only works for apps installed through Play.

## Licence test checklist (Galaxy A14, Android 15)

Record PASS / FAIL and the observation for each line.

1. Fresh install, no purchase: the Settings screen shows "Version gratuite" and the price loaded from Play.
2. Free recording shows the PRO lock; the paywall lists the features and the Play price.
3. Tap *Acheter PRO* with a license-tester account: the Play sheet opens, the purchase completes, the app shows "Merci ! PRO est activé à vie." and unlocks recording, history, zoom, compare, export and backup, without restarting.
4. Close and reopen the app: PRO is still active (cache), even in airplane mode.
5. Clear the app data (or reinstall), sign in with the same Google account, tap *Restaurer mes achats*: PRO comes back.
6. Refund the test order in Play Console (*Orders*), then restart the app with network: PRO is revoked. Restart with airplane mode first: PRO stays (offline), revoked only once Play answers.
7. Cancel the Play sheet: "Achat annulé." and the app stays free.
8. Pending purchase (slow payment method if available): "Achat en attente", PRO not active until approved.

Google Play does not allow this to be tested without a Play-installed build and the Play account. None of it has been executed yet.

## Known limitations

- There is no server-side verification (SensorLab has no server). A rooted phone could edit the local cache. The cache is the offline convenience, not a DRM. For a stronger check, add server-side purchase verification later.
- The licence is tied to the Google account that bought it, as Play defines it.
