# CallerX — বাংলা ব্যবহারকারী গাইড

CallerX একটি Android ডায়ালার + SIP/VoIP অ্যাপ। SIM কল সাধারণ ডায়ালারের মতোই কাজ করে,
আর তার উপরে SIP অ্যাকাউন্ট যোগ করা যায়।

## ১. প্রথমবার খোলা

1. অ্যাপ খুলুন → অনুমতি দিন (Contacts, Call log, Phone, Microphone)।
2. **ডিফল্ট ডায়ালার** বানাতে চাইলে Settings → *Dialer & Default Permission* → Grant all permissions।
3. উপরের ডান দিকের পিলে SIP স্ট্যাটাস দেখায়: সবুজ ডট = অনলাইন, "Offline" = কোনো অ্যাকাউন্ট নেই।

## ১ক. সেটিংস কীভাবে সাজানো (v1.5.36)

Settings ট্যাব এখন এক লম্বা পেজ নয় — **১১টি গ্রুপ**। প্রতিটি গ্রুপের নিচে বর্তমান অবস্থা লেখা থাকে,
গ্রুপে ট্যাপ করলে শুধু ওই গ্রুপের অপশন খোলে, আর Back চাপলে গ্রুপ লিস্টে ফেরে।

| গ্রুপ | কী পাবেন |
|---|---|
| SIP account & PBX | অ্যাকাউন্ট, PBX park/pickup, forwarding, SIP log, latency, re-register |
| Calling & SIM | default route, Ask before each call, SIM 1/2, speed dial, voicemail, call waiting, time format |
| Audio & codec | audio output, headset, Bluetooth, ringtone, vibration, keypad tone, quiet hours, codec, diagnostics |
| Security & privacy | TLS, SRTP, blocked numbers, block before ringing, block unknown callers, export/import |
| Missed calls & voicemail | auto-SMS, reply text, voicemail number |
| Recording & quality | auto recording, recordings, call quality |
| Backup & restore | profile, auto backup folder, password, restore parts |
| Appearance | theme color, dark mode, AMOLED, text size |
| App lock & call alerts | PIN, skip PIN in calls, lock-screen calls, full-screen alerts, notification settings |
| Dialer & permissions | default dialer, permissions, power mode, autostart, battery |
| App updates & support | updates, auto check, audio diagnostics, crash log |

> পূর্ণ তালিকা ও পরবর্তী ফিচার পরিকল্পনা: `docs/SETTINGS-AND-ROADMAP-BN.md`

## ২. SIP অ্যাকাউন্ট যোগ করা

1. Settings → **SIP Account** → ➕।
2. ইউজারনেম, পাসওয়ার্ড, ডোমেইন (যেমন `sip.provider.com`), পোর্ট (5060 UDP/TCP, 5061 TLS) দিন।
3. ট্রান্সপোর্ট বাছুন: **UDP** (সবচেয়ে সাধারণ), **TCP**, **TLS** (এনক্রিপ্টেড), **WSS** (ফায়ারওয়ালের পেছনে)।
4. Save → পিল সবুজ হলে কল করা যাবে।
5. একাধিক অ্যাকাউন্ট থাকলে ডায়াল করার সময় কোনটা ব্যবহার করবেন বেছে নিতে পারবেন।

> কল ড্রপ হলে: Settings → *Encrypted SIP transport (TLS)* / *SRTP* বন্ধ করে দেখুন, অনেক প্রোভাইডার
> শুধু UDP-তে কাজ করে। SIP পিলে ট্যাপ করে **Measure** দিয়ে সার্ভার লেটেন্সিও দেখা যায়।

## ৩. কল করা

- **ডায়ালপ্যাড**: নম্বর লিখে সবুজ বাটন। বক্সে লম্বা-চাপ দিলে পেস্ট হয়।
- **স্পিড ডায়াল**: ১–৯ চেপে ধরে রাখুন (Settings → Speed dial-এ সেট করুন)।
- **ভয়েসমেইল**: ১ চেপে ধরে রাখুন (Settings → Voicemail number)।
- **কল রাউট**: প্রতি কলে SIP/SIM বাছতে Settings → *Ask before each call*।

## ৪. কল চলাকালীন

| বাটন | কাজ |
|---|---|
| Mute / Speaker | মাইক বন্ধ / স্পিকার |
| Keypad | DTMF টোন (IVR মেনু, PIN) — শুধু SIP কলে |
| Hold | কল হোল্ড |
| Add call | দ্বিতীয় কল; তারপর Merge করে কনফারেন্স |
| Transfer | Blind (REFER) বা Attended (দুই কল থাকলে) |
| Record | কল রেকর্ডিং (Settings → Call recordings-এ শোনা/শেয়ার) |

- **Call waiting**: দ্বিতীয় কল এলে "CALL WAITING" কার্ড — *Answer & hold* বা *Decline*।
- **ফরওয়ার্ডিং**: Settings → Call forwarding (always / busy / no answer, ১০–৬০ সেকেন্ড)।
- **PBX কোড**: Transfer শিটে Park/Pickup চিপ (Settings → PBX feature codes)।

## ৫. ব্যাকআপ ও রিস্টোর

- **Save backup to Google Drive**: সিস্টেম পিকার → Drive/Dropbox/স্টোরেজ।
- **Automatic backup & restore**: একটি ফোল্ডার বাছে রাখুন → সর্বোচ্চ ৬ ঘণ্টায় একবার
  `callerx-autobackup.json` + তারিখওয়ালা ৭টি স্ন্যাপশট লেখে। নতুন ফোনে একই ফোল্ডার বাছলেই রিস্টোর।
- **Backup password**: চালু করলে সব নতুন ব্যাকআপ AES-256-GCM দিয়ে এনক্রিপ্ট হয়।

## ৬. প্রাইভেসি

- **Blocked numbers**: `+88017*` এর মতো ওয়াইল্ডকার্ড কাজ করে।
- **Block before ringing**: চালু থাকলে ব্লকড নম্বর রিং হওয়ার আগেই আটকে যায় (call-screening role দরকার)।
- **App lock (PIN)**: অ্যাপ খুললে ৪-ডিজিট PIN।
- **Auto-SMS on missed call**: SIP কল মিস হলে কলারকে টেক্সট রিপ্লাই (SIM চার্জ প্রযোজ্য)।

## ৬ক. কল আর লক স্ক্রিন

- **Show calls over the lock screen** (ডিফল্ট চালু): ফোন লক থাকলেও কল এলে কল-স্ক্রিন লক স্ক্রিনের
  উপরে আসবে। বন্ধ করলে শুধু নোটিফিকেশন আসবে।
- **Skip app PIN during calls** (ডিফল্ট চালু): অ্যাপ-lock PIN দিয়ে অ্যাপ লক করা থাকলেও কলের সময়
  PIN পর্দা কলকে ঢেকে দেবে না; কল শেষ হলে আবার PIN চাইবে।
- **Full-screen call alerts**: Android 14+ এ কোনো কোনো ফোনে কল-স্ক্রিন লক স্ক্রিনে দেখাতে
  বাধা দেয়। এই সারিতে ট্যাপ করে অনুমতি দিলে কল সাথে সাথেই পূর্ণ পর্দায় আসবে।

> দয়া করে মনে রাখবেন: ফোন যদি কিস্তি/ফাইন্যান্স লক (PayJoy, MDM ইত্যাদি) দিয়ে আটকানো থাকে,
> সেটি কোনো অ্যাপ দিয়ে খোলা যায় না — শুধু বিক্রেতা/ফাইন্যান্স কোম্পানি খুলতে পারে।

## ৬খ. হেডসেট ও কল-অডিও

- হেডসেট লাগালে/খুললে টোস্ট দেখাবে: **"USB-C headset connected · call audio routed"**।
- **Call audio only through headset** চালু করলে কল-অডিও কখনো ফোনের স্পিকারে যাবে না।
- **Settings → Audio diagnostics → Test tone**: যে রুটে সাউন্ড যাচ্ছে সেখানে ১.৫ সেকেন্ড টোন বাজবে —
  হেডসেট নীরব থাকলে সাথে সাথে বোঝা যাবে।
- নীরব থাকলে: ⋮ → Audio output → **USB-C headset** বেছে নিন, আর Android-কে Bluetooth/হেডসেট
  পারমিশন দিন।

## ৬গ. অ্যাপ আপডেট (ইন-অ্যাপ)

- নতুন ভার্সন এলে অ্যাপ খুললেই **"Update available · vX"** ডায়ালগ আসবে (রিলিজ নোটসহ)।
- **Update now** চাপলে APK অ্যাপের ভেতরেই ডাউনলোড হয়, সিগনেচার যাচাই হয়, তারপর Android ইনস্টলার খোলে
  → **Install** চাপলেই আপডেট শেষ। GitHub-এ যেতে হবে না।
- প্রথমবার Android **"Install unknown apps"** পারমিশন চাইবে (Settings → App updates থেকে সেটিংসে যাওয়া যায়)।
- Settings → **App updates**: হাতে চেক; **Check for updates automatically** বন্ধ করলে নিজে থেকে চেক হবে না।

> Android নিজে থেকে (নিঃশব্দে) সাইডলোড APK ইনস্টল করতে দেয় না — শেষ **Install** ট্যাপটা OS-এর নিরাপত্তা।
> সম্পূর্ণ অটোমেটিক আপডেট চাইলে Play Store-এ পাবলিশ করতে হবে।

## ৭. সমস্যা হলে

1. SIP অনলাইন হচ্ছে না → ইউজার/পাস/ডোমেইন/পোর্ট/ট্রান্সপোর্ট মিলিয়ে দেখুন; TLS বন্ধ করে দেখুন।
2. কল কেটে যাচ্ছে → Adaptive audio চালু রাখুন; Wi-Fi/ডেটা বদলে দেখুন।
3. ব্যাটারি অপ্টিমাইজেশন বন্ধ → Settings → Power Mode।
4. কিছু ভাঙলে → Settings → **Crash log** → শেয়ার করে ডেভেলপারকে পাঠান।
