# CallerX

**CallerX** is a native Android SIP/VoIP dialer. Its UI is rendered directly from the supplied `CallerX-Complete-UI.html` design and its launcher icon uses the supplied CallerX logo.

## Included

- Supplied CallerX HTML UI (Calls, Messages, Contacts, Settings, SIP, Call screens, and all supplied navigation)
- SIP calling with UDP, TCP, and TLS via Linphone
- SIP account storage and registration
- Foreground SIP service, wake lock, incoming-call notification, and lock-screen CallerX UI
- Call controls connected to the native SIP engine: mute, speaker route, hold, end call, and opt-in auto recording
- Runtime microphone and Android 13+ notification permission handling
- Existing “Set as Default Dialer” action opens Android’s supported default-dialer request

## Build

Requirements: Android SDK platform 34 and JDK 17.

```bash
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Automated APK

Every push to the working branch runs GitHub Actions, uploads an APK artifact, and updates the rolling [`dev-latest` release](https://github.com/mdreanislam01-ai/REAN-SIP/releases/tag/dev-latest) with an APK and SHA-256 checksum.
