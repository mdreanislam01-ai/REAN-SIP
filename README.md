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

**<https://github.com/mdreanislam01-ai/REAN-SIP/releases/tag/callerx-v1.5.15>**

## Notes

- This is a debug build, signed with the default debug key. For Play Store distribution a release keystore is required.
- The native Android app uses standard SIP (UDP/TCP/TLS). The `CallerX.html` prototype uses SIP over secure WebSocket (WSS) + WebRTC in the browser.
- In-app purchases / cloud backup / remote update checks are UI previews; they are not wired to a backend.

## v1.5.36 — silent headset fix (liblinphone route + audio stream rebuild)

- `routeLinphoneDevices` no longer falls back to the **earpiece** when Android reports a headset
  that liblinphone's own device list does not contain. That fallback was overriding the USB-C
  route after AudioManager had already been moved, which is why the headset stayed silent in
  CallerX while other dialers worked.
- Picking a route during a call now **rebuilds the SIP audio stream** (`pause()` → `resume()`), so
  the audio track that was already running on the speaker moves to the headset (~1 s of silence).
- New **Restart call audio** button in ⋮ → Audio output, and a setting *Move audio to a new headset
  automatically* (on by default) that rebuilds the stream when a headset is plugged in mid-call.

## v1.5.35 — in-app updates (no more GitHub downloads)

- On start (at most every 6 hours) CallerX asks the GitHub release feed for `callerx-v*` and, if
  the build is older, shows **"Update available · vX"** with the release notes.
- **Update now**: the APK for this phone's ABI is downloaded inside the app
  (`cache/updates/`), then checked before the installer opens —
  package name, `versionName` newer than the installed one, and the **APK signature must match
  the installed app's certificate** (a tampered or foreign APK is refused).
- Android needs **"Install unknown apps"** for CallerX once (Settings → App updates leads there);
  after that an update is: tap *Update now* → tap *Install* in the system dialog. Android does not
  allow a sideloaded app to install silently — that last confirmation cannot be removed without
  Play Store or a system signature.
- Settings → *App updates* (manual check) and *Check for updates automatically*.

## v1.5.34 — the headset actually gets the call audio

- **Root cause found**: the in-call route poller passed the engine's `speakerPreferred` flag,
  which was never reset between calls — so once Speaker had been used, every later call was
  forced back onto the loudspeaker even though the UI showed Speaker off and a headset was
  plugged in. The poller now follows the Speaker state on screen, and both flags reset when a
  call starts.
- A headset connected **during** a call takes the audio back from the speaker, unless the user
  pressed Speaker *after* plugging it in (same behaviour as Google/Samsung dialers).
- **Audio diagnostics** additionally lists liblinphone's own audio devices and its current output
  device, which distinguishes "Android routed it but liblinphone didn't" from "Android never saw
  the headset".

## v1.5.33 — headset feedback, headset-only audio, test tone

- **Plug / unplug toast**: "USB-C headset connected · call audio routed" (or Bluetooth / wired,
  and "Headset disconnected · call audio on the phone"). Works during a call via the route poller
  and while the app is open via a light foreground watcher.
- **Call audio only through headset** (Settings): speaker and earpiece are skipped for call
  audio; pressing Speaker explains instead of switching.
- **Test tone** in Settings → Audio diagnostics: plays 1.5 s of DTMF through the selected route,
  so you can hear immediately whether the headset is really receiving call audio.

## v1.5.32 — Type-C (USB-C) headset audio

- **USB-C earphones now route for calls**: `USB_HEADSET`, `USB_DEVICE`, `USB_ACCESSORY`,
  `LINE_ANALOG`, `AUX_LINE` and hearing aids are treated as headsets, `USB-C headset` is its own
  entry in the audio picker (Settings → Audio output and in-call ⋮), and the route is re-applied
  0.25 s / 0.8 s / 1.6 s into a call because USB audio devices attach slowly.
- **`AudioDeviceCallback`** is registered: plugging the Type-C headset in *during* a call reroutes
  at once. `ACTION_HEADSET_PLUG` simply never fires for USB-C on Xiaomi/HyperOS and many ROMs.
- **Settings → Audio diagnostics**: lists mode, SCO state, communication device and every output /
  input device with its type and product name — shareable, so a silent headset can be diagnosed
  from the phone itself.

## v1.5.31 — missed calls disappear once you have seen them

- The **Calls tab badge counts only unseen missed calls**; opening the Calls tab or the Missed
  filter (≈0.7 s) clears the badge and cancels the missed-call notification.
- Missed calls now go into **one aggregated notification** ("3 missed calls · Last: … · number")
  instead of replacing each other, and tapping it opens the **Missed** list (it used to open the
  dial pad).
- The badge is hidden while the Calls tab itself is open, so it no longer flashes on launch.

## v1.5.30 — no more force-close during calls, working Bluetooth audio

- The `onCallStateChanged` handler runs inside `mainHandler.post`; **any** exception there killed
  the app ("CallerX force closed due to an internal error"). It is now fully guarded and logged
  to the shareable error log (Settings → Crash log).
- **Audio routing rewritten**: a manual route (⋮ → *Audio output*: automatic / earpiece /
  speaker / Bluetooth / wired) always wins, liblinphone only overrides when it actually lists
  the headset, Bluetooth SCO is retried when the headset appears late, and routing starts after
  the streams are up instead of while the audio HAL is still negotiating.
- **Settings → Bluetooth call audio** requests the Android 12+ permission; the in-call picker
  warns when the headset is music-only (A2DP, no HFP) — that case cannot carry call audio.
- **Network handover**: Wi-Fi ⇄ mobile data re-registers SIP quietly and never during a call.

## v1.5.29 — a ringing call shows up on the lock screen

- `MainActivity` now declares `android:showWhenLocked`/`turnScreenOn` and applies
  `setShowWhenLocked()`/`setTurnScreenOn()` + `requestDismissKeyguard()` for swipe locks, so a
  call appears *over* the lock screen instead of leaving the phone on the lock screen.
  A secure lock (PIN/pattern/biometric) is never bypassed — Android still asks the owner.
- The app-lock PIN keypad no longer covers an incoming or ongoing call.
- Settings → *Calls & lock screen*: **Show calls over the lock screen**, **Skip app PIN during
  calls**, and **Full-screen call alerts** (opens the Android 14+ full-screen-intent screen).

## v1.5.28 — the call screen stays put, fewer buttons, contact search

- **Call screen no longer disappears** while calling: the screen state now survives Activity
  recreation (`rememberSaveable`), any return to the app during a call re-opens the live call,
  and the "call ended" navigation re-checks the state so a new call is never closed.
- **In-call controls trimmed**: Mute / Speaker / Hold / ⋮ stay on screen; Keypad (DTMF),
  Add call, Transfer, Record, Merge, Swap and Share moved into the ⋮ menu.
- **Call-log search finds contacts too**: the same name/number/T9 matching as the contacts tab,
  rendered as a *Contacts* section under the call rows.

## v1.5.27 — unit tests in CI

- `android/app/src/test/java/com/callerx/app/NumberRulesTest.kt` tests the `+880` / `880` /
  `00880` / `0` number equivalence and the exact / suffix / wildcard blocking matcher.
- CI runs `:app:testDebugUnitTest` before building, so a broken rule now fails the pipeline
  instead of shipping.

## v1.5.26 — blocklist import/export, Bangla resources

- **Blocked list as a file**: Settings → *Export blocked list* shares a plain text file;
  *Import blocked list* merges one number (or `+88017*` wildcard) per line — commas, spaces
  and semicolons also work.
- **বাংলা resources**: `res/values-bn/strings.xml` translates the app name, widget, tile and
  shortcut labels; the Bangla walkthrough lives in `docs/USER-GUIDE-BN.md`.

## v1.5.25 — smaller downloads, selective restore, wide screens

- **Per-ABI APKs**: every release now carries `app-arm64-v8a-debug.apk` (modern phones, much
  smaller download) alongside the universal `app-debug.apk`.
- **Selective restore**: Settings → *Restore parts of a backup* → tick SIP accounts/history,
  speed dials & favorites, blocked numbers and/or app settings.
- **Wide-screen / landscape layout**: the whole UI is capped to a centred 520 dp column.

## v1.5.24 — encrypted backups, call screening, appearance

- **Encrypted backups**: optional password → every new backup is AES-256-GCM with
  PBKDF2-SHA256 (120k rounds); restore decrypts when the password is known and otherwise asks
  for it. Files stay ordinary documents for Drive/Dropbox (`CALLERX-ENC1:` prefix).
- **Block before ringing**: `CallerXScreeningService` rejects blocked and wildcard-matched
  numbers before the ring (needs the call-screening role). Toggle: Settings → *Block before ringing*.
- **AMOLED black** theme + **text size** (85–130%) applied to the whole Compose tree.
- **Full-screen caller photo** on the call screen and a **live quality sparkline**.
- **App shortcuts** (New SIP call, Voicemail, Favorites, Recordings), **transfer quick-picks**,
  **PBX park/pickup codes** as one-tap DTMF chips, **delete-all for a grouped number**,
  a separate **missed-call notification channel**, an **offline crash log** (Settings → Crash log)
  and **WSS** as a SIP transport option.
- Docs: `docs/USER-GUIDE-BN.md` (Bangla walkthrough), `docs/AUDIT.md` (repo audit),
  `docs/PLAY-STORE.md` (listing draft), `scripts/changelog.sh` (release notes from commits).

## v1.5.23 — forwarding, favorites, quality report, 7-day backups

- **Call forwarding** (Settings → Call forwarding): forward all / when busy / when unanswered,
  with a 10–60 s no-answer timer. Implemented with `Call.redirectTo()` (SIP **302 Moved
  Temporarily**) — honest note: the SIP server must honour 302; providers that do not need
  their own star codes.
- **Favorites tab** in the bottom bar: starred contacts only, with call, message and
  un-star actions; empty state explains how to star a contact.
- **Call-log grouping**: one row per number with a `· N calls` counter (newest entry wins),
  filters and swipe actions unchanged.
- **Call quality report** (Settings → Call quality report): for the last 30 SIP calls it shows
  a MOS estimate plus the real RTCP values — round-trip delay, jitter buffer, remote/local
  packet loss, bandwidth up/down and the SRTP suite in use. MOS is computed from those
  numbers, not measured by a probe.
- **Recording player**: seek bar with live position/duration and 0.5× / 1× / 1.5× / 2× speed
  (`PlaybackParams`) next to play, share and delete.
- **Versioned automatic backups**: the chosen folder now receives `callerx-autobackup.json`
  (used for restore) plus dated snapshots, keeping the newest 7 and pruning older ones.

## v1.5.22 — call waiting, recordings, widget, auto restore

- **Call waiting**: a second incoming SIP call no longer overwrites the live call screen —
  a "Call waiting" card shows *Answer & hold* / *Decline*, the current call is paused
  automatically and the ringtone stops as soon as the waiting call is handled.
- **Call recordings screen** (Settings → Call recordings): play, share (FileProvider) and
  delete recordings inside the app; nothing leaves the phone unless you share it.
- **Adaptive audio**: if `currentQuality` stays below ~1.6 for two samples the live call is
  renegotiated with `setLowBandwidthEnabled(true)` so it stops breaking up. G.729 is
  patent-encumbered and not part of the open-source linphone build, so the fallback is
  low-bandwidth narrowband rather than a specific codec. Settings → *Adaptive audio*.
- **Home-screen widget** (`CallerXWidgetProvider`) with live SIP status and three shortcuts
  (dial pad, speed dial 1, voicemail) plus a **Quick Settings tile** (`CallerXDialTile`).
  The engine writes `status:online` / `status:account` to preferences and repaints the widget
  on every registration change.
- **Automatic backup & restore**: pick a folder once (Google Drive, Dropbox, phone storage)
  via the system folder picker with persisted access. `callerx-autobackup.json` is refreshed
  at most every 6 hours, and picking that folder on a new install restores the settings
  automatically (`applyBackupJson`).
- **Signed release**: `./scripts/make-keystore.sh` creates the upload keystore and prints the
  four GitHub secrets; CI then builds `app-release.apk` + `.aab`, runs `apksigner verify`
  and uploads both. Without the secrets the debug APK is still published.

## v1.5.21 — in-call extras, missed-call handling

- **DTMF keypad during a call**: press *Keypad* on the in-call screen and every tone is sent
  to the other side (IVR menus, conference PINs, extension dialling). Sent digits are shown
  with Clear/Close; tones use RFC 2833 events so no audio is clipped.
- **Call transfer**: *Transfer* → enter a number or SIP address → **Blind** (REFER, you leave
  the call) or, when two calls exist via *Add call*, **Attended** (both remote parties are
  joined and CallerX drops out).
- **Missed-call auto-SMS**: when a SIP call is missed (or declined), a normal SMS reply is
  sent to the caller. Message text is editable in Settings → Missed calls & voicemail; SMS
  sending permission is requested only when the toggle is switched on. Blocked numbers never
  get a reply.
- **Voicemail**: save your voicemail number and hold **1** on the dial pad to call it, like a
  stock dialer (a stored speed dial on 1 still wins).
- SRTP is now applied per call (outgoing params + `acceptWithParams` on answer) because
  `AccountParams` in linphone-sdk 5.4 has no media-encryption setter.

## v1.5.18 — privacy & convenience pack

- Swipe a recent call right to call it, left to delete it from the call log.
- App lock with a 4-digit PIN (Settings → App lock (PIN)); calls still ring behind the lock.
- Missed calls get **Call back**; quick-reply templates; share a contact as vCard.
- Incoming vibration style: short / double / long.
- Call-log filter chips now include **SIP**, **SIM 1**, **SIM 2**; blocked numbers accept
  wildcards such as `+88017*`.
- Speed dial manager (Settings → Speed dial) and a per-call SIP account picker when several
  accounts are enabled.
- End call hangs up every call, including a merged conference.

## v1.5.17 — headset audio + add call / conference

- Headphone audio works during SIP calls: routing uses the Android `AudioManager`
  communication devices (Bluetooth SCO → wired/USB headset → earpiece → speaker) and
  re-routes automatically when a headset is plugged or a Bluetooth device connects mid-call.
  A "Headset" chip confirms the active route on the call screen.
- **Add call** holds the current call and dials a second number; with two calls the screen
  lists both with **Swap** and **Merge** (local SIP conference).

## v1.5.16 — polish pack

- Contact photos in recents and contacts (initials as fallback), missed-call badge (24 h)
  on the Calls tab, live mm:ss timer in the ongoing-call notification.
- Tap the header chip for a SIP status popup (account, server:port, transport, state).
- Speed dial: ⋮ → **Speed dial** on a recent call, then hold that key in the dial pad.
- Recent searches appear as chips under the search bar.
- Dark mode uses a darker shade of the accent for the header.
- Registration retries back off 10 s → 20 s → 40 s → 80 s (max 120 s), resetting after success.
- Silent weekly backup to phone storage.

### Release signing (optional)

Copy `keystore.properties.example` to `keystore.properties` in the repo root and point it at
your keystore; `./gradlew assembleRelease` then produces a properly signed APK. Both the
properties file and `*.jks` are git-ignored. Without it, release builds fall back to the
debug key.

## v1.5.15 — SIP account chip, Profile & Backup, calmer scrolling

- Header chip shows the SIP account name (green dot = registered, amber = connecting) and
  "Offline" when no account is added.
- Settings → **Profile & Backup Account** with real backup: Google account picker (registered
  with Android's Google Drive backup) and a JSON file in `Downloads/CallerX`, plus Restore.
- Calmer fling physics on call history / contacts / messages so scrolling stops where you
  want it.

## v1.5.14 — dialpad header in the app theme colour

- The top strip of the dial pad (status bar + "CallerX" row) is painted with the theme accent
  instead of white, with white status-bar icons — the same treatment as the home header.

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
