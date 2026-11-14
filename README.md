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

**<https://github.com/mdreanislam01-ai/REAN-SIP/releases/tag/callerx-v1.5.2>**

## Notes

- This is a debug build, signed with the default debug key. For Play Store distribution a release keystore is required.
- The native Android app uses standard SIP (UDP/TCP/TLS). The `CallerX.html` prototype uses SIP over secure WebSocket (WSS) + WebRTC in the browser.
- In-app purchases / cloud backup / remote update checks are UI previews; they are not wired to a backend.
