# Rean Dialer

**Rean Dialer** is a private, native Android SIP/VoIP dialer built with Kotlin and the professional Linphone SIP engine.

## Highlights

- SIP calling over UDP, TCP, or TLS
- Add, edit, and switch between multiple personal SIP accounts
- Foreground SIP service for dependable incoming calls and lock-screen call UI
- Native dialpad, DTMF keypad, mute, speaker routing, and hold/resume controls
- Live in-call codec and call-quality feedback
- On-device call history; no analytics or cloud tracking
- English and বাংলা interface, selected automatically by device language
- Ocean Blue dark interface, optimized for Android 8.0+ (API 26)

> No SIP server is preconfigured. Add your own account under **Accounts**.

## Build locally

Requirements: Android SDK platform 34 and JDK 17.

```bash
./gradlew assembleDebug
```

The debug APK is produced at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Download APK

Every push builds a debug APK in GitHub Actions and updates the rolling [`dev-latest` release](https://github.com/mdreanislam01-ai/REAN-SIP/releases/tag/dev-latest). Download its APK and `checksums.sha256`, verify the checksum, then open the APK on your Android device to install.

## Privacy

SIP credentials and call history are stored locally on the device. Rean Dialer does not include tracking, advertising SDKs, or account telemetry.
