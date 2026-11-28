# CallerX সেটিংস — নতুন গঠন ও পরবর্তী ফিচার পরিকল্পনা

> এই ফাইলটি v1.5.36-এ করা সেটিংস পুনর্গঠন এবং "এর পরে কী কী করা যায়" তার তালিকা।
> নিচের সবকিছু বাংলায়, কিন্তু মেনু/অপশনের নাম ইংরেজিতেই রাখা হয়েছে যাতে অ্যাপের স্ক্রিনের সাথে মেলে।

---

## ১. আগে সমস্যা কী ছিল

আগে Settings ট্যাবে **৪৫+ অপশন একটানা এক পেজে** ছিল (Account → VoIP → SIM → Missed calls →
General → Security → Audio → Updates → Theme)। ফলে:

- একটা অপশন খুঁজতে অনেক নিচে/উপরে স্ক্রল করতে হতো — "অনেক লম্বা পেজ"।
- কোন অপশন কোন কাজের, তার গ্রুপিং ছিল না।
- কয়েকটা রো ছিল শুধু দেখানোর জন্য — চালু/বন্ধ করলে আসলে কিছুই হতো না:
  *Keypad tone, Call waiting, Voicemail over SIP* — এই ধরনের অপশনগুলোর পেছনে কোনো কোড ছিল না।

## ২. এখন কী হয়েছে (v1.5.36)

### ২ক. প্রথম পেজ — শুধু ১১টি গ্রুপ

প্রতিটি গ্রুপের নিচে **বর্তমান অবস্থা** (summary) দেখা যায়, তাই না ঢুকেই বোঝা যায় কী চালু আছে:

| গ্রুপ | ভেতরে কী আছে |
|---|---|
| SIP account & PBX | SIP account, PBX park/pickup কোড, Call forwarding, SIP log, latency, re-register |
| Calling & SIM | Default route, Ask before each call, SIM 1/2, Speed dial editor, Voicemail (SIP/SIM), Call waiting, Time format |
| Audio & codec | Audio output, Headset only, Bluetooth, Ringtone, Vibration, Keypad tones, Louder ring, Silent during call, Quiet hours, Adaptive audio, Codecs, Diagnostics |
| Security & privacy | TLS, SRTP, Blocked numbers, Block before ringing, Block unknown callers, Export/Import blocked list |
| Missed calls & voicemail | Auto-SMS, Auto-reply text, Voicemail number |
| Recording & quality | Auto recording, Recordings, Call quality report |
| Backup & restore | Profile, Auto backup folder, Backup password, Restore parts, Save now, Phone backup, Restore |
| Appearance | Theme color, Dark mode, AMOLED, Text size |
| App lock & call alerts | App lock PIN, Skip PIN during calls, Calls over lock screen, Full-screen alerts, Android notification settings |
| Dialer & permissions | Default dialer, Android permissions, Power mode, OEM autostart, Battery optimisation |
| App updates & support | App updates, Auto update check, Audio diagnostics, Crash log |

### ২খ. গ্রুপ পেজে কীভাবে কাজ করে

- গ্রুপে ট্যাপ → শুধু ওই গ্রুপের অপশন, ছোট পেজ।
- **সিস্টেম Back** চাপলে গ্রুপ পেজ → গ্রুপ লিস্ট → Calls স্ক্রিন (অ্যাপ বন্ধ হয় না)।
- অ্যাপ রিক্রিয়েট হলেও কোন গ্রুপ খোলা ছিল সেটা মনে থাকে।
- ক্লিকযোগ্য রো-গুলোতে clamp করা হয়েছে **সাবটাইটেলে কী হবে** সেটা লেখা থাকে ("Tap to …"), আর টগল রো-গুলোতে সেটেল করে দিলেই সাথে সাথে কার্যকর হয়।

### ২গ. যেসব অপশন এখন সত্যিই কাজ করে

| অপশন | কী করে |
|---|---|
| Quiet hours | নির্দিষ্ট সময়ে কল আসলে রিং/ভাইব্রেশন বন্ধ, কিন্তু কল স্ক্রিনে কল আসে |
| Keypad tones | বন্ধ করলে ডায়ালপ্যাড নীরব (tone বাজে না) |
| Louder ringtone | পুরো ভলিউমে রিং (নাহলে ৬০%) |
| Silent during call | অন্য কলে থাকা অবস্থায় দ্বিতীয় কল রিং/ভাইব্রেট করে না |
| Call waiting | বন্ধ থাকলে দ্বিতীয় কলার "busy" পায় |
| Voicemail over SIP | Hold 1 চাপলে SIM-এর বদলে SIP লাইন দিয়ে ভয়েসমেইল |
| Time format | কল হিস্টোরিতে 24-ঘণ্টা ফরম্যাট |
| Block unknown callers | কন্ট্যাক্টে না থাকা নম্বর decline + auto-reply SMS |
| Speed dial editor | ১–৯ কী-তে নম্বর সেট/মুছুন |
| Re-register SIP | আটকে থাকা "Registering…" ঠিক করতে এক ট্যাপে রি-রেজিস্টার |
| OEM autostart / Battery | Xiaomi/Oppo/Vivo/Huawei-এর autostart স্ক্রিন ও battery exemption |
| SIP log summary / Latency | SIP ইতিহাস সারসংক্ষেপ ও সার্ভার TCP লেটেন্সি মাপা |

---

## ৩. এর পরে কী কী ফিচার যোগ করা যায়

নিচের তালিকা অগ্রাধিকার অনুযায়ী সাজানো। প্রতিটির পাশে "কেন দরকার" ও "কীভাবে করা যায়" সংক্ষেপে।

### ৩ক. যেগুলো আগে করা উচিত (ছোট কাজ, বড় লাভ)

1. **Settings-এ সার্চ (খোঁজা)**
   উপরে একটা সার্চ বক্স — "ring", "bh" টাইপ করলেই গ্রুপ পেজের ভেতরের অপশনগুলোতে ফিল্টার।
   *কেন:* গ্রুপ থাকলেও কেউ কেউ নাম মনে রাখে না।
   *কীভাবে:* প্রতিটি রো-এর title/subtitle + গ্রুপ id একটি লিস্টে রেখে `matchesQuery` দিয়ে ফিল্টার।

2. **প্রতি-contact রিংটোন ও ভাইব্রেশন**
   Contact detail → Ringtone → নির্দিষ্ট নম্বরের জন্য আলাদা রিং।
   *কেন:* পরিবার/অফিস আলাদা করে চেনা যায়।
   *কীভাবে:* `prefs: "ring:<number>"` সেভ করে `startIncomingRingtone()`-এ চেক।

3. **DND / Quiet hours-এ ব্যতিক্রম (Favorites always ring)**
   Quiet hours চলাকালেও Favorites রিং করবে — টগল।
   *কেন:* জরুরি কল মিস না হওয়া।
   *কীভাবে:* `quietHoursActive()`-এ `fav:<number>` চেক।

4. **পার-অ্যাকাউন্ট রিংটোন/রেজিস্ট্রেশন টাইমার**
   SIP account পেজে expiry (৩০০s) ও keepalive (১৫s) editable — এখন হার্ডকোড করা।

5. **Call summary / monthly stats**
   Settings → Recording & quality-তে "এই মাসে মোট কল, মোট সময়, মিসড %"।
   *কীভাবে:* বিদ্যমান `quality:log` ও SIP history থেকে হিসাব, আলাদা করে কিছু সেভ করতে হবে না।

### ৩খ. মাঝারি কাজ

6. **SIP provider template (এক ট্যাপে সেটআপ)**
   সাধারণ provider (যেমন কোন কোম্পানি, PBX, Asterisk, FreePBX) — domain/port/transport অটো-ফিল।
   *কেন:* নতুন ইউজারকে ট্রান্সপোর্ট/পোর্ট ভুল করতে হয় না।

7. **Call recording ফরম্যাট ও কোয়ালিটি সেটিং**
   AMR/WAV বেছে নেওয়া, স্টেরিও/মনো, অটো-ডিলিট (৩০/৬০ দিন পর)।
   *কেন:* মেমোরি ভরে যায়।

8. **Whitelist mode (শুধু কন্ট্যাক্ট থেকে কল)**
   "Block unknown" এর উল্টো/কড়া ভার্সন — শুধু অনুমোদিত নম্বর রিং করবে।
   *কেন:* স্প্যাম/VoIP স্প্যাম থেকে বাঁচা।

9. **সময়ভিত্তিক রুল (Time-based rules)**
   অফিস টাইমে SIP, বাইরে SIM — শিডিউল করা রাউটিং।
   *কীভাবে:* `route:default` কে "time window" লিস্টে বদলে দেওয়া।

10. **Multiple profiles (Work / Personal)**
    অ্যাকাউন্ট, রিং, ফরওয়ার্ডিং একসাথে বদলানোর প্রোফাইল সেট।

11. **Auto-answer (headset / Bluetooth)**
    হেডসেটে কল এলে নির্দিষ্ট সেকেন্ড পরে অটো রিসিভ।

12. **Voicemail greeting / নিজের ভয়েসমেইল**
    কল মিস হলে শুধু SMS নয়, SIP provider-এর voicemail এ forward।

### ৩গ. বড় ফিচার (নতুন subsystem দরকার)

13. **Call transcription / AI summary**
    রেকর্ডিং থেকে টেক্সট (on-device speech recognizer API) + সারাংশ। বাংলা সাপোর্ট OS-এর উপর নির্ভরশীল।
14. **Spam score / caller reputation**
    নিজের ব্লকলিস্ট + (ভবিষ্যতে) ক্লাউড ডাটাবেস থেকে স্কোর দেখানো।
15. **Backup/password → biometrics**
    PIN-এর বদলে fingerprint/face দিয়ে অ্যাপ লক (BiometricPrompt)।
16. **Wi-Fi only mode**
    মোবাইল ডাটায় SIP রেজিস্ট্রেশন বন্ধ (ডাটা বাঁচাতে)।
17. **Video call / screen share**
    Linphone SDK সাপোর্ট করে, কিন্তু UI + ব্যান্ডউইথ টেস্ট দরকার।
18. **Multi-device / SIP fork**
    একই অ্যাকাউন্টে ফোন+ট্যাব — উভয়েই রিং।
19. **Backup → Supabase/Notion/Drive API**
    বর্তমানে ব্যাকআপ লোকাল JSON। চাইলে Drive API বা Supabase-এ sync করা যায় (এখন শুধু ফাইল পিকার)।
20. **Theme: dynamic color (Material You)**
    Android 12+ এর ওয়ালপেপার থেকে রঙ নেওয়া — এখন ৯টি ফিক্সড রঙ।

---

## ৪. দ্রুত যা টেস্ট করা দরকার (QA checklist)

- [ ] Settings খুললে ১১টি গ্রুপ দেখা যায়, প্রতিটির নিচে সঠিক summary।
- [ ] যেকোনো গ্রুপে ঢুকে Back চাপলে গ্রুপ লিস্টে ফেরে (অ্যাপ বন্ধ হয় না)।
- [ ] Quiet hours চালু করে সময় নির্ধারণ → কল এলে রিং/ভাইব্রেশন বন্ধ, কল আসে।
- [ ] Block unknown callers চালু → অজানা নম্বর decline + SMS।
- [ ] Call waiting বন্ধ → দ্বিতীয় কল busy পায়।
- [ ] Speed dial editor-এ ২ সেট করে ডায়ালপ্যাডে ২ চেপে ধরে কল।
- [ ] Voicemail over SIP চালু/বন্ধ — hold 1 দুই ভাবে কাজ করে।
- [ ] SIP re-register → পিল আবার Online হয়।
- [ ] Battery optimisation → "CallerX is exempt" দেখায়।
- [ ] ব্যাকআপ পেজ থেকে Save now → ফাইল পিকার খোলে।
