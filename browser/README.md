# REAN Browser

Candy Browser-এর আদলে বানানো একটি Android browser (Android 8.0+).
Rendering engine হিসেবে Android System WebView / Chromium ব্যবহার করা হয়েছে।

## Features
- Tabs (+ tab switcher), নতুন tab, popup tab হিসেবে খোলা
- Address bar: URL লিখলে সরাসরি খুলবে, না হলে DuckDuckGo-তে search
- Back / forward / reload / home, page progress bar
- History ও Bookmarks (on-device, JSON হিসেবে SharedPreferences-এ)
- Incognito tab (history/bookmark রাখে না; শেষ incognito tab বন্ধ হলে cookies ও site storage মুছে যায়)
- Ad & tracker blocking (host-based) এবং script-opened popup block
- Dark mode (WebView algorithmic darkening)
- Download manager integration (Downloads folder-এ save, notification সহ)
- Share page, external browser-এ খোলা, find on page, home page সেটিং
- http/https link-এর জন্য default browser হিসেবে register করা যায়

## Build
GitHub Actions workflow `Build REAN Browser APK` প্রতিটি `browser/**` পরিবর্তনে debug APK বানায়।
Artifact: `REAN-Browser-debug-apk`।

Local build (JDK 17, Android SDK 35, Gradle 8.7):
```bash
cd browser
gradle :app:assembleDebug
```

## Not included (Candy Browser-এর যেসব feature এখনো নেই)
- Gecko engine, add-on support, Mozilla Sync / Candy Sync
- RSS inbox, reader mode, tab stacks, pause/resume downloads
- True per-tab cookie isolation for incognito (WebView সীমাবদ্ধতা)
