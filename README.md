# REAN-SIP / CallerX

**CallerX** — নেটিভ Android SIP/VoIP ডায়ালার (Jetpack Compose + Linphone SDK)।
The UI is built from the supplied `CallerX.html` design (Calls, Messages, Contacts, Settings, SIP, profile, theme, blocked numbers, codec settings, permissions, power mode).

## Features

- নেটিভ Jetpack Compose UI — dialer keypad, recents, messages, contacts, settings
- SIP calling (UDP / TCP / TLS / WSS) via Linphone SDK 5.4
- Incoming/outgoing native call screens, mute, speaker, hold, end call
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

**<https://github.com/mdreanislam01-ai/REAN-SIP/releases/tag/callerx-v1.4.2>**

## Notes

- This is a debug build, signed with the default debug key. For Play Store distribution a release keystore is required.
- SIP registration requires a SIP provider with secure WSS and WebRTC audio enabled.
- In-app purchases / cloud backup / remote update checks are UI previews; they are not wired to a backend.
