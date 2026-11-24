# CallerX — বাংলা ব্যবহারকারী গাইড

CallerX একটি Android ডায়ালার + SIP/VoIP অ্যাপ। SIM কল সাধারণ ডায়ালারের মতোই কাজ করে,
আর তার উপরে SIP অ্যাকাউন্ট যোগ করা যায়।

## ১. প্রথমবার খোলা

1. অ্যাপ খুলুন → অনুমতি দিন (Contacts, Call log, Phone, Microphone)।
2. **ডিফল্ট ডায়ালার** বানাতে চাইলে Settings → *Dialer & Default Permission* → Grant all permissions।
3. উপরের ডান দিকের পিলে SIP স্ট্যাটাস দেখায়: সবুজ ডট = অনলাইন, "Offline" = কোনো অ্যাকাউন্ট নেই।

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

## ৭. সমস্যা হলে

1. SIP অনলাইন হচ্ছে না → ইউজার/পাস/ডোমেইন/পোর্ট/ট্রান্সপোর্ট মিলিয়ে দেখুন; TLS বন্ধ করে দেখুন।
2. কল কেটে যাচ্ছে → Adaptive audio চালু রাখুন; Wi-Fi/ডেটা বদলে দেখুন।
3. ব্যাটারি অপ্টিমাইজেশন বন্ধ → Settings → Power Mode।
4. কিছু ভাঙলে → Settings → **Crash log** → শেয়ার করে ডেভেলপারকে পাঠান।
