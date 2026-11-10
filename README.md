# 𝕏 Lite (Android) — Airtel 64kbps FUP Edition

A clean, standalone, ultra-low-bandwidth native Android client for 𝕏 (Twitter) designed specifically to run seamlessly on throttled connections like **Airtel's 64 kbps / 128 kbps FUP**.

---

## 🚀 Key Features

* **100% On-Phone Compute**: Runs entirely on your Android device — zero external proxy, server, or Mac required.
* **Clean, Authentic Minimal UI**: Pure `#000000` OLED dark aesthetic matching official 𝕏, free of bloated animations or flashy distractions.
* **Local feed cache**: Recent feeds open immediately without a network request. The app refreshes a cached feed after five minutes or when you tap Refresh.
* **Conversation Threads & Replies**: Read full tweet threads and post replies directly with minimal payloads.
* **In-App Hot Updates (OTA)**: Pull web interface updates directly in Settings. Native networking changes require a new APK.
* **Direct Twitter GraphQL Engine**: Uses native `OkHttp` with Brotli and Gzip compression to speak directly to Twitter's internal endpoints.
* **Search**: Generates X's per-request transaction header on the phone, using X's current web page materials. Search setup needs an extra download on first use; those materials are reused for 15 minutes.
* **Data-saving images**: Photos stay unloaded until tapped by default. A tap requests X's thumbnail variant before recompressing it on the phone; higher quality is optional. Actual bytes and loading time depend on X's response and the cellular link.

The repository has a manually triggered **Live X search smoke test** workflow. It reads `XLITE_TEST_AUTH_TOKEN` and `XLITE_TEST_CT0` from GitHub Actions secrets and reports only whether a search response was parsed successfully.

---

## 📥 Download APK

Grab the latest compiled APK directly from the Releases tab:
* **[Download the latest `xlite-fup-release.apk`](https://github.com/sovopr/xlite-android/releases/latest/download/xlite-fup-release.apk)**

---

## ⚙️ Setup (30 Seconds)

1. Install the APK on your phone.
2. In your desktop browser (where you are logged into [x.com](https://x.com)):
   * Open Developer Tools (`Cmd + Option + I` on Mac or `F12` on Windows).
   * Go to **Storage** (Safari) or **Application** (Chrome/Brave) &rarr; **Cookies** &rarr; `https://x.com`.
   * Copy the value of `auth_token` and `ct0`.
3. Open **𝕏 Lite** on your phone &rarr; tap **⚙ Settings** &rarr; paste the tokens &rarr; tap **Save Configuration**.
