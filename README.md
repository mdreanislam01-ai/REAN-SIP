# REAN-SIP / CallerX

**CallerX** — নেটিভ Android SIP/VoIP ডায়ালার (Jetpack Compose + Linphone SDK)।
The UI is built from the supplied `CallerX.html` design (Calls, Messages, Contacts, Settings, SIP, profile, theme, blocked numbers, codec settings, permissions, power mode).

## Features

- নেটিভ Jetpack Compose UI — dialer keypad, recents, messages, contacts, settings
- SIP calling (UDP / TCP / TLS) via Linphone SDK 5.4
- Incoming call ringtone + vibration, outgoing call ringback tone (bundled WAV tones)
- STUN/ICE NAT traversal so calls work behind mobile carrier NAT
- Incoming/outgoing native call screens, mute, speaker, hold, end call
- Call screen shows live call duration, network type + call quality (Wi-Fi / 5G / 4G LTE, Excellent → Poor) and recording status
- Real call recording (Record button + Auto Call Recording), saved inside app storage
- Keypad: editable number field with Paste / Copy / Clear, haptic keys, hold 0 for "+", auto-clears after a call
- Background-loaded contacts, call log & SMS with ContentObserver auto-refresh (no UI lag, no stale cache)
- Debounced smart search on Calls / Contacts / Messages (name, number, number ending, T9 letters, initials)
- Back button never closes the app — it steps back to the previous page / main page (call log), and minimises instead of exiting
- Ongoing-call notification with Mute / Speaker / End actions
- Multi-account SIP manager (add/edit/delete, enable, default account, Auto-Boot, UDP/TCP/TLS, proxy)
- Default call route (SIP / SIM 1 / SIM 2) so calls never ask twice; per-contact defaults
- Call-method sheet with SIP status badge and SIM carrier cards
- Redesigned contact details (call history + total talk time, caller ID card, tags, notes, block)
- App-wide accent theming (9 colors) + dark mode across every screen
- Android dialer role qualification, cellular calls routed through the selected SIM account
- Runtime microphone, notification, SMS and contacts permission handling
- Dark mode, theme colors, blocked numbers, codec settings — persisted on-device
- `CallerX.html` — the original supplied interactive design/prototype (reference)

## Build

Requirements: Android SDK platform 35, Build Tools 35.0.0, JDK 17, Gradle 8.7.

```bash
gradle :app:assembleDebug
```

The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

## Automated APK

Every push that touches `CallerX.html`, `android/**`, or the Gradle/workflow files runs the **Build CallerX APK** GitHub Actions workflow. It builds the debug APK, uploads it as a workflow artifact, and publishes it to the rolling GitHub release:

**<https://github.com/mdreanislam01-ai/REAN-SIP/releases/tag/callerx-v1.5.13>**

## Notes

- This is a debug build, signed with the default debug key. For Play Store distribution a release keystore is required.
- The native Android app uses standard SIP (UDP/TCP/TLS). The `CallerX.html` prototype uses SIP over secure WebSocket (WSS) + WebRTC in the browser.
- In-app purchases / cloud backup / remote update checks are UI previews; they are not wired to a backend.

## v1.5.13 — dialpad number box above the keypad

- The typed number lives in a rounded box **just above the keypad** (copy left, backspace
  right) instead of at the very top of the screen; long-press the box to paste a copied
  number, and the extra Paste button in the top bar is gone.
- A light "CallerX" heading stays visible at the top next to the back arrow.
- Contact suggestions keep listing matching numbers between the heading and the number box.
- Status bar icons follow the screen colour (dark icons on the light dialer, white on the
  coloured header), and the legacy `systemUiVisibility` flags no longer fight the modern
  insets controller.

## v1.5.12 — header, status bar and Truecaller-style dialpad

- Header is three clean lines: **CallerX**, then **SIM 1 · SIM 2 · SIP**, then a single
  full-width search bar inside the coloured header (it was previously hidden behind a
  magnifier toggle and overlapped the title). The right side shows only **Online** /
  **Offline**.
- The status bar (clock / Wi-Fi / battery) is painted with the app theme colour instead of
  a white strip, with white icons; the dialer keeps a light bar with dark icons. The
  duplicate status-bar padding on Android 15 that left a white gap is gone.
- Dialpad now follows Truecaller: paste icon top-right, copy icon left of the number,
  backspace right of it (long-press clears all), matched contact name above the number,
  flat keys with DTMF tones + haptics, and a centred green call button.
- Call-history filter chips scroll sideways, so the **Clear** button no longer wraps.
- Audio routing honours a connected Bluetooth / wired headset (SCO started) and the
  speaker toggle.

## v1.5.11 — lock-screen mic, network drops, search & dialpad

- SIP engine runs in the foreground service (microphone FGS type), independent of the Activity:
  locking the phone / minimising / system killing the Activity no longer stops the microphone
  or drops the call. Incoming SIP calls use a full-screen intent over the lock screen.
- Audio focus + `MODE_IN_COMMUNICATION`, wake/Wi-Fi locks from first ring, Bluetooth/wired
  headset routing, and a boot receiver that re-registers SIP after reboot.
- Network stability: 15 s SIP keepalive, 5-minute registration refresh, Opus mono 16 kHz
  (was 48 kHz stereo), re-register on network-available only (not on lost).
- Search: text keyboard on all tabs, auto-focus, +880/880/00/0 equivalence, true T9 search.
- Dialpad: Google-Dialer style keys with ripple, DTMF tones, haptics, live contact
  suggestions, Paste/Copy/Clear, + key, conditional backspace.
