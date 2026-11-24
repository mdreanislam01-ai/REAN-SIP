# CallerX — repo audit (v1.5.24)

Read-only review of the current tree. No third-party service was used.

## Structure

| Area | Where | Note |
|---|---|---|
| UI + engine | `android/app/src/main/java/com/callerx/app/MainActivity.kt` (~5.5k lines) | One file holds SipEngine, MainActivity and ~45 composables. Works, but it is the main maintenance risk. |
| Widget / tile / screens | `CallerXWidgetProvider.kt`, `CallerXDialTile.kt`, `CallerXScreeningService.kt`, `CallerXApplication.kt` | Small and independent. |
| Resources | `res/layout`, `res/xml`, `res/drawable`, `res/values` | Widget, shortcuts, FileProvider paths. |
| CI | `.github/workflows/android-apk.yml` | Debug APK + optional signed APK/AAB, error summary in annotations. |

## Risks found

1. **Single 5.5k-line file** — highest churn, slowest reviews. Suggested split: `SipEngine.kt`,
   `MainActivity.kt`, `ui/*.kt` (dialer, calls, settings, call screen).
2. **Preferences as database** — SIP history and quality log are JSON strings inside
   SharedPreferences. Fine to a few hundred calls; a Room table is the scalable next step.
3. **No tests** — every regression is caught by the CI compiler only. Pure helpers
   (`canonicalDigits`, MOS estimate, backup encode/decode) are easy first unit tests.
4. **Main-thread media work** — `MediaPlayer.prepare()` runs on the main thread for local files
   (small files, acceptable) and `Ringtone` is created per incoming call (fine, released on stop).
5. **Compose state from IO** — `resumeTick++` is called from `Dispatchers.IO` in two backup paths.
   Snapshot state is thread-safe, but calling it on the main thread is cleaner.
6. **Release build signs with the debug key** unless `keystore.properties` / CI secrets exist.
   Documented in README and `scripts/make-keystore.sh`.
7. **Battery** — the foreground service holds a wake lock during calls only; keep-alive uses
   SIP OPTIONS every 20 s. On aggressive OEMs, Power Mode (unrestricted) is still required.
8. **Permissions** — 20 declared; `SEND_SMS` is requested only when the user enables auto-SMS,
   `BLUETOOTH_CONNECT` only on Android 12+.

## Good practices already in place

- No analytics, no ad SDK, no network calls except the SIP stack itself.
- Backups are local/SAF only; nothing is uploaded by CallerX.
- All risky platform APIs are wrapped in `try/catch` so a missing permission never crashes.
- Sensible CI: build first, publish only on success, optional signing.

## Suggested order of work

1. Split `MainActivity.kt` into `SipEngine` + UI files (mechanical, big win).
2. Room table for SIP history + quality log.
3. Unit tests for helpers + a smoke test for backup round-trip.
4. ABI splits (`arm64-v8a`, `armeabi-v7a`) to cut the APK from ~94 MB to ~35 MB per device.
